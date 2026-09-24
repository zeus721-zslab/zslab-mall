package com.zslab.mall.order.repository;

import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 주문 품목 Repository(QB-5 JpaRepository 단일·메서드 이름 쿼리 + 경량 projection).
 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long>, JpaSpecificationExecutor<OrderItem> {

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);

    /** 상품에 주문 이력이 있는지(Track 76·관리자 soft-delete 차단 409). 상태 무관·1건이라도 있으면 true. */
    boolean existsByProductId(Long productId);

    /**
     * 한 주문의 OrderItem을 일괄 물리삭제한다(FE-12c-2·미결제 종료 주문 hard delete 자식 정리). 벌크 DELETE로 1회 실행하며
     * 삭제 건수를 반환한다. 삭제는 부모 order 삭제에 선행해야 한다(fk_order_item_order RESTRICT). 모든 변수는 :orderId 바인딩이다.
     */
    @Modifying
    @Query("DELETE FROM OrderItem oi WHERE oi.order.id = :orderId")
    int deleteByOrderId(@Param("orderId") Long orderId);

    /**
     * OrderItem을 public_id(oit_)로 조회한다. 외부 요청의 public_id를 BIGINT id로 해소하는 진입점 패턴이다
     * (D-64·D-65 정합·OrderRepository.findByPublicId와 1:1). {@code ClaimService.request}가 소유권·전이 검증에 사용한다.
     */
    Optional<OrderItem> findByPublicId(String publicId);

    /**
     * 주문 품목의 소속 order_id만 조회한다(Track 5 환불 흐름·Claim → Payment 해소용 경량 projection).
     * OrderItem.order는 getter를 노출하지 않으므로(Aggregate 단방향) FK 컬럼 값만 읽는다. 모든 변수는 :id 바인딩이다.
     */
    @Query("SELECT oi.order.id FROM OrderItem oi WHERE oi.id = :id")
    Optional<Long> findOrderIdById(@Param("id") Long id);

    /**
     * {@link #findOrderIdById}의 public_id 판(Track 104-1 D-215·주문 쓰기 락 대상 해소). 엔티티를 적재하지 않으므로 주문 락 전에
     * 불러도 1차 캐시에 품목이 남지 않는다. 모든 변수는 :publicId 바인딩이다.
     */
    @Query("SELECT oi.order.id FROM OrderItem oi WHERE oi.publicId = :publicId")
    Optional<Long> findOrderIdByPublicId(@Param("publicId") String publicId);

    /** 주문에 특정 상태 품목이 있는지(Track 104-2 D-216·확정 품목 있는 전액 환불 판정). 파생 쿼리 바인딩. */
    boolean existsByOrderIdAndItemStatus(Long orderId, OrderItemStatus itemStatus);

    /** 구매자 주문에 제외 상태(itemStatuses) 밖의 품목이 있는지(Track 104-4 탈퇴 가드 결제 후 단계·P4). 파생 쿼리 바인딩. */
    boolean existsByOrderBuyerIdAndItemStatusNotIn(Long buyerId, Collection<OrderItemStatus> itemStatuses);

    /**
     * 여러 주문 품목의 소속 주문 요약(id·public_id·주문번호·구매자 id)과 품목 상품명을 한 번에 조회한다(Track 80 관리자 클레임 목록
     * 배치 enrich·N+1 회피·Order 엔티티 미적재). 상품명은 Track 101-B 외부 검토 반영으로 더했다 — 구매자 클레임 목록이 같은
     * itemIds로 {@code findAllById}를 한 번 더 돌려 OrderItem 엔티티를 적재하던 것을 이 스칼라 1쿼리로 합쳤다. 상품 id는 Track 105-4b
     * 구매자 클레임 목록 썸네일 배치 조회 키로 더했다. 모든 변수는 :ids 바인딩이다.
     */
    @Query("SELECT oi.id AS orderItemId, o.id AS orderId, o.publicId AS orderPublicId, o.orderNo AS orderNo, "
            + "o.buyerId AS buyerId, oi.productName AS productName, oi.productId AS productId "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.id IN :ids")
    List<OrderItemOrderProjection> findOrderSummariesByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 셀러 품목 목록·상세의 주문 축 표시값(주문번호·주문일시·결제일시)을 한 번에 조회한다(Track 90-B-1·N+1 회피·Order 엔티티 미적재).
     * 구매자 id·주문 총액 등 셀러 노출 금지 필드는 projection에 넣지 않는다. 모든 변수는 :ids 바인딩이다.
     */
    @Query("SELECT oi.id AS orderItemId, o.orderNo AS orderNo, o.orderedAt AS orderedAt, o.paidAt AS paidAt "
            + "FROM OrderItem oi JOIN oi.order o WHERE oi.id IN :ids")
    List<SellerOrderItemOrderProjection> findSellerOrderSummariesByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * 정산 기간 내 구매확정(CONFIRMED) 품목의 총 매출(total_price 합)을 seller별로 집계한다(Track 48 P2·정산 gross 소스).
     * 기간 기준은 {@code confirmed_at}이며 경계는 양끝 포함({@code >= periodStart AND <= periodEnd})이다.
     * {@code confirmed_at IS NULL}(미확정) 행은 범위 비교가 false로 평가돼 자연 제외된다.
     *
     * <p>{@code status}는 enum 바인딩 파라미터로 전달한다(@Enumerated(STRING) 정합·JPQL enum 리터럴 ordinal 비교 함정 회피).
     * 모든 변수는 :status·:periodStart·:periodEnd 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT oi.sellerId AS sellerId, COALESCE(SUM(oi.totalPrice), 0) AS grossAmount "
            + "FROM OrderItem oi "
            + "WHERE oi.itemStatus = :status "
            + "AND oi.confirmedAt >= :periodStart "
            + "AND oi.confirmedAt <= :periodEnd "
            + "GROUP BY oi.sellerId")
    List<SellerGrossProjection> aggregateGrossBySeller(
            @Param("status") OrderItemStatus status,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd);

    /**
     * 기간 말까지 구매확정(CONFIRMED)됐고 아직 어느 정산에도 편입되지 않은 품목을 스냅샷 소스로 조회한다(Track 85·settlement_item SALE ·
     * Track 104-3b 결정 ⑦). 기간 하한이 없으므로 앞선 기간에 빠진 사실(보류 해제·늦은 확정 등)도 다음 정산에 들어온다 — 편입 여부는
     * settlement_item (SALE, source_id) 전역 UNIQUE 키로 판정한다. 열린(OPEN) 불일치가 있는 주문의 품목은 제외한다(보류·결정 ⑥ ·
     * ix_reconciliation_issue_order_status). sellerId가 null이면 전 셀러, 아니면 해당 셀러만(재생성 — 같은 트랜잭션에서 지운 자기 품목은
     * 미편입으로 보여 다시 집계된다). 주문 public_id는 {@code oi.order} 조인 네비게이션으로 얻는다(OrderItem은 order getter 미노출·엔티티 미적재).
     * 모든 변수는 :status·:periodEnd·:sellerId 바인딩만 사용하며 SQL injection 위험이 없다.
     */
    @Query("SELECT oi.id AS orderItemId, oi.sellerId AS sellerId, o.publicId AS orderPublicId, "
            + "oi.productName AS productName, oi.optionLabel AS optionLabel, oi.quantity AS quantity, "
            + "oi.totalPrice AS amount, oi.commissionRate AS commissionRate, oi.confirmedAt AS confirmedAt "
            + "FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.itemStatus = :status "
            + "AND oi.confirmedAt <= :periodEnd "
            + "AND (:sellerId IS NULL OR oi.sellerId = :sellerId) "
            + "AND NOT EXISTS (SELECT 1 FROM SettlementItem si "
            + "WHERE si.itemType = com.zslab.mall.settlement.enums.SettlementItemType.SALE AND si.sourceId = oi.id) "
            + "AND NOT EXISTS (SELECT 1 FROM ReconciliationIssue ri "
            + "WHERE ri.orderId = o.id AND ri.status = com.zslab.mall.reconciliation.enums.ReconciliationIssueStatus.OPEN) "
            + "ORDER BY oi.sellerId, oi.confirmedAt, oi.id")
    List<SettlementSaleSourceProjection> findSettlementSaleSources(
            @Param("status") OrderItemStatus status,
            @Param("periodEnd") LocalDateTime periodEnd,
            @Param("sellerId") Long sellerId);

    /**
     * buyer의 생애 누적 구매액(구매확정 품목 total_price 합)을 집계한다(Track 51 등급 산정 입력·recon-report §R1).
     * buyer_id는 Order에만 존재하므로 {@code oi.order.buyerId}로 조인 네비게이션한다(OrderItem은 buyer_id 미보유).
     * 기간 필터 없음(생애 누적)이며 {@code COALESCE(...,0)}로 구매확정 이력이 없는 buyer도 0을 반환한다.
     *
     * <p>환불 차감 없음: 환불·취소·반품·교환 품목은 CANCELLED·RETURNED·EXCHANGED(종결)로 전이돼 CONFIRMED와 상호배타이며,
     * CONFIRMED 이후 클레임은 전건 차단된다(D-88 Q3). 따라서 {@code status = CONFIRMED} 필터가 환불 가치를 원천 배제한다.
     * {@code status}는 enum 바인딩 파라미터로 전달한다(seller 집계와 동일·ordinal 비교 함정 회피). 모든 변수는 :바인딩이다.
     */
    @Query("SELECT COALESCE(SUM(oi.totalPrice), 0) "
            + "FROM OrderItem oi "
            + "WHERE oi.order.buyerId = :buyerId "
            + "AND oi.itemStatus = :status")
    long sumConfirmedTotalPriceByBuyerId(
            @Param("buyerId") Long buyerId,
            @Param("status") OrderItemStatus status);

    /**
     * 구매자 주문 품목의 상태별 건수(Track 105-2d 마이페이지 주문 현황). 기간 기준은 주문일({@code ordered_at})이며 하한 포함이다.
     * 집계 대상 상태(itemStatuses) 밖의 품목은 조회 단계에서 제외되므로 0건 상태는 결과 행이 없다(0 채움은 호출부).
     * 모든 변수는 :buyerId·:orderedFrom·:itemStatuses 바인딩이다(SQL injection 위험 없음).
     */
    @Query("SELECT oi.itemStatus AS itemStatus, COUNT(oi) AS itemCount FROM OrderItem oi JOIN oi.order o "
            + "WHERE o.buyerId = :buyerId AND o.orderedAt >= :orderedFrom AND oi.itemStatus IN :itemStatuses "
            + "GROUP BY oi.itemStatus")
    List<ItemStatusCountProjection> countByBuyerIdGroupByItemStatus(
            @Param("buyerId") Long buyerId,
            @Param("orderedFrom") LocalDateTime orderedFrom,
            @Param("itemStatuses") Collection<OrderItemStatus> itemStatuses);

    /**
     * 셀러의 진행 중 품목 수(Track 89-D 종료 가드 G2·D-187). 진행 중 = 품목 상태가 종결 4종(CONFIRMED·CANCELLED·RETURNED·EXCHANGED)이
     * 아니면서 주문이 닫힌 상태(closedOrderStatuses — 가드는 미결제 종료 PAYMENT_EXPIRED)도 아닌 것. <b>주문 상태 조인이 필수</b>다 — 결제 만료 주문의 품목은
     * {@code item_status=ORDERED}로 남아(품목 전이 없음·정찰 실측) 품목 상태만 보면 만료 주문이 진행 중으로 잡힌다.
     * PENDING_PAYMENT(미결제·만료 전)는 결제될 수 있어 진행 중으로 센다. 모든 변수는 :sellerId·:terminalItemStatuses·:closedOrderStatuses 바인딩이다.
     */
    @Query("SELECT COUNT(oi) FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.sellerId = :sellerId "
            + "AND oi.itemStatus NOT IN :terminalItemStatuses "
            + "AND o.status NOT IN :closedOrderStatuses")
    long countInProgressBySellerId(
            @Param("sellerId") Long sellerId,
            @Param("terminalItemStatuses") Collection<OrderItemStatus> terminalItemStatuses,
            @Param("closedOrderStatuses") Collection<OrderStatus> closedOrderStatuses);

    /**
     * 셀러 누적 거래 요약(Track 89-D 관리자 셀러 상세): 주문 수 = 미결제(PENDING_PAYMENT)·미결제 종료(PAYMENT_EXPIRED)를 제외한
     * 결제 이력 있는 주문의 DISTINCT 수, 누적 매출 = 구매확정(CONFIRMED) 품목 total_price 합(정산 gross와 같은 기준·
     * {@link #aggregateGrossBySeller} 정합). 모든 변수는 :sellerId·:excludedOrderStatuses·:confirmedStatus 바인딩이다.
     */
    @Query("SELECT COUNT(DISTINCT o.id) AS orderCount, "
            + "COALESCE(SUM(CASE WHEN oi.itemStatus = :confirmedStatus THEN oi.totalPrice ELSE 0 END), 0) AS confirmedAmount "
            + "FROM OrderItem oi JOIN oi.order o "
            + "WHERE oi.sellerId = :sellerId AND o.status NOT IN :excludedOrderStatuses")
    SellerSalesSummaryProjection summarizeSalesBySellerId(
            @Param("sellerId") Long sellerId,
            @Param("excludedOrderStatuses") Collection<OrderStatus> excludedOrderStatuses,
            @Param("confirmedStatus") OrderItemStatus confirmedStatus);
}
