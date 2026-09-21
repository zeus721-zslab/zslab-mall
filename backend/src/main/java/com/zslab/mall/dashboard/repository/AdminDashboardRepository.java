package com.zslab.mall.dashboard.repository;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.refund.enums.RefundStatus;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.settlement.enums.SettlementStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 대시보드 전용 집계 리포지토리(Track 86·D-180). 도메인 Repository를 오염시키지 않기 위해 대시보드 쿼리를 한 곳에 모으며,
 * CRUD를 노출하지 않는 {@link Repository} 마커만 상속한다(도메인 타입 Order는 형식상 지정·JPQL은 여러 엔티티를 대상으로 한다).
 *
 * <p>기간 경계는 서비스가 KST LocalDateTime으로 계산해 바인딩하며 모든 범위 조건은 반구간({@code >= from AND < to})이다.
 * 저장값은 KST 벽시계(JVM TZ·hibernate.jdbc.time_zone 모두 Asia/Seoul)이므로 DATE_FORMAT 구간 문자열도 KST 날짜다.
 * 네이티브 쿼리를 쓰지 않으며(기존 관례) 모든 변수는 :바인딩만 사용해 SQL injection 위험이 없다.
 */
public interface AdminDashboardRepository extends Repository<Order, Long> {

    /** 기간 결제완료 주문 건수·total_price 합(+discount·shipping_fee 합·응답 미노출). 행 0건이면 COALESCE로 0. */
    @Query("SELECT COUNT(o) AS orderCount, COALESCE(SUM(o.totalPrice), 0) AS revenue, "
            + "COALESCE(SUM(o.discountAmount), 0) AS discountAmount, COALESCE(SUM(o.shippingFee), 0) AS shippingFee "
            + "FROM Order o WHERE o.paidAt >= :from AND o.paidAt < :to")
    DashboardSalesProjection sumSales(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 기간 완료 환불액 합(refunded_at 기준). status는 enum 바인딩(ordinal 비교 함정 회피). */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r "
            + "WHERE r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to")
    long sumRefund(@Param("status") RefundStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 기간 신규회원 수(created_at 기준·role 보유자·탈퇴자 포함 — 가입 시점 사실·D-180).
     * role 판정은 AdminMemberSpecifications.buyerRole과 같은 user_role 서브쿼리다.
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :from AND u.createdAt < :to "
            + "AND u.id IN (SELECT ur.userId FROM UserRole ur WHERE ur.role.code = :role)")
    long countNewMembers(@Param("role") RoleCode role, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(s) FROM Settlement s WHERE s.status = :status")
    long countSettlementsByStatus(@Param("status") SettlementStatus status);

    @Query("SELECT COUNT(c) FROM Claim c WHERE c.status = :status")
    long countClaimsByStatus(@Param("status") ClaimStatus status);

    /** 배송 대기 = 송장 미등록 품목. 송장 등록이 PAID→PREPARING→SHIPPING을 1TX로 전이하므로 PAID가 곧 대기 상태다(recon §2-4). */
    @Query("SELECT COUNT(oi) FROM OrderItem oi WHERE oi.itemStatus = :status")
    long countOrderItemsByStatus(@Param("status") OrderItemStatus status);

    /** 재고 임박 = 가용 재고가 [min, max] 구간인 variant 수. 수동 품절(variant·product) 처리분은 판매 의도가 없어 제외한다. */
    @Query("SELECT COUNT(i) FROM Inventory i, ProductVariant v, Product p "
            + "WHERE i.variantId = v.id AND v.productId = p.id "
            + "AND v.soldoutManual = false AND p.soldoutManual = false "
            + "AND i.quantityAvailable >= :min AND i.quantityAvailable <= :max")
    long countLowStock(@Param("min") int min, @Param("max") int max);

    /** 상품 승인 대기(Track 96-2 D-203·C-01). Product의 {@code @SQLRestriction(deleted_at IS NULL)}로 삭제 상품은 자동 제외된다. */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.status = :status")
    long countProductsByStatus(@Param("status") ProductStatus status);

    /** 셀러 승인 대기(Track 96-2 D-203·C-01). Seller의 {@code @SQLRestriction(deleted_at IS NULL)}로 삭제 셀러는 자동 제외된다. */
    @Query("SELECT COUNT(s) FROM Seller s WHERE s.status = :status")
    long countSellersByStatus(@Param("status") SellerStatus status);

    /** 구간별 결제완료 주문 건수·total_price 합. pattern은 월별 "%Y-%m"·일별 "%Y-%m-%d"이며 서비스 상수만 전달한다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', o.paidAt, :pattern) AS bucket, COUNT(o) AS orderCount, "
            + "COALESCE(SUM(o.totalPrice), 0) AS amount "
            + "FROM Order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', o.paidAt, :pattern)")
    List<DashboardBucketProjection> sumSalesByBucket(@Param("pattern") String pattern,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 구간별 완료 환불 건수·amount 합(refunded_at 기준). pattern 규약은 {@link #sumSalesByBucket}와 같다. */
    @Query("SELECT FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) AS bucket, COUNT(r) AS orderCount, "
            + "COALESCE(SUM(r.amount), 0) AS amount "
            + "FROM Refund r WHERE r.status = :status AND r.refundedAt >= :from AND r.refundedAt < :to "
            + "GROUP BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern) "
            + "ORDER BY FUNCTION('DATE_FORMAT', r.refundedAt, :pattern)")
    List<DashboardBucketProjection> sumRefundByBucket(@Param("pattern") String pattern,
            @Param("status") RefundStatus status, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 최근 결제완료 주문(paid_at 내림차순·동시각은 id 내림차순). 건수는 Pageable로 제한하며 paid_at 인덱스(V31) 역방향 스캔이다. */
    @Query("SELECT o.publicId AS orderPublicId, o.orderNo AS orderNo, o.buyerId AS buyerId, o.totalPrice AS totalPrice, "
            + "o.paidAt AS paidAt, o.status AS status "
            + "FROM Order o WHERE o.paidAt IS NOT NULL ORDER BY o.paidAt DESC, o.id DESC")
    List<DashboardRecentOrderProjection> findRecentPaidOrders(Pageable pageable);

    /** 최근 클레임(requested_at 내림차순). Claim·OrderItem은 Long 참조라 theta-join·주문번호는 oi.order 네비게이션. */
    @Query("SELECT c.publicId AS claimPublicId, c.type AS type, c.status AS status, o.orderNo AS orderNo, "
            + "c.requestedAt AS requestedAt "
            + "FROM Claim c, OrderItem oi JOIN oi.order o WHERE c.orderItemId = oi.id "
            + "ORDER BY c.requestedAt DESC, c.id DESC")
    List<DashboardRecentClaimProjection> findRecentClaims(Pageable pageable);

    /**
     * 기간 결제완료 주문의 셀러별 품목 매출 합 상위 N. 품목 상태로 제외하지 않는다 — 정산 산출(SettlementCreationService.collectSources)도
     * 취소·반품 품목을 상태 필터로 걸러내지 않고 "매출 품목 합 − 환불(COMPLETED refund) 합"으로 상쇄하며, 대시보드는 같은 구조를
     * 결제완료 기준(paid_at)으로 옮긴 것이다(환불은 summary.refund에서 별도 차감·D-180).
     */
    @Query("SELECT oi.sellerId AS sellerId, COALESCE(SUM(oi.totalPrice), 0) AS revenue, COUNT(oi) AS orderItemCount "
            + "FROM OrderItem oi JOIN oi.order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.sellerId ORDER BY SUM(oi.totalPrice) DESC, oi.sellerId ASC")
    List<DashboardTopSellerProjection> findTopSellers(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            Pageable pageable);

    /** 기간 결제완료 주문의 상품별 품목 매출 합 상위 N. 상태 미제외 근거는 {@link #findTopSellers}와 같다. 상품명은 주문 시점 스냅샷. */
    @Query("SELECT oi.productId AS productId, MAX(oi.productName) AS productName, "
            + "COALESCE(SUM(oi.totalPrice), 0) AS revenue, COALESCE(SUM(oi.quantity), 0) AS quantity "
            + "FROM OrderItem oi JOIN oi.order o WHERE o.paidAt >= :from AND o.paidAt < :to "
            + "GROUP BY oi.productId ORDER BY SUM(oi.totalPrice) DESC, oi.productId ASC")
    List<DashboardTopProductProjection> findTopProducts(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
            Pageable pageable);
}
