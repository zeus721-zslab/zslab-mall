package com.zslab.mall.demoseed.repository;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.review.enums.ReviewStatus;
import com.zslab.mall.user.entity.User;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 데모 시더(D-244·D-245) 전용 조회·시각 보정. 도메인 저장소에 데모 전용 메서드를 섞지 않으려고 따로 둔다. 쓰기는 도메인 서비스가 하고,
 * 여기서 쓰는 것은 이번 호출에서 만든 질문·리뷰 행의 시각 보정뿐이다.
 */
public interface DemoSeedRepository extends Repository<ProductQuestion, Long> {

    /**
     * 질문 작성자 후보: 데모 도메인 이메일 · BUYER 역할 · 탈퇴 전(삭제분은 엔티티 제한으로 제외). 셀러 구성원은 자기·타 셀러 상품에 질문하는 모양이
     * 부자연스러워 제외한다. id 순이라 배정이 결정적이다.
     */
    @Query("SELECT u FROM User u WHERE u.email LIKE :emailPattern AND u.withdrawnAt IS NULL "
            + "AND EXISTS (SELECT 1 FROM UserRole ur WHERE ur.userId = u.id AND ur.role.code = :buyerRole) "
            + "AND NOT EXISTS (SELECT 1 FROM SellerUser su WHERE su.userId = u.id) ORDER BY u.id")
    List<User> findDemoBuyers(@Param("emailPattern") String emailPattern, @Param("buyerRole") RoleCode buyerRole);

    /** 대상 셀러들의 해당 상태 상품(삭제분은 엔티티 제한으로 제외) · id 순. */
    @Query("SELECT p FROM Product p WHERE p.sellerId IN :sellerIds AND p.status = :status ORDER BY p.id")
    List<Product> findProductsBySellerIdsAndStatus(@Param("sellerIds") Collection<Long> sellerIds, @Param("status") ProductStatus status);

    /** 상품들의 질문 전체(숨김 포함 · 삭제분은 엔티티 제한으로 제외). */
    @Query("SELECT q FROM ProductQuestion q WHERE q.productId IN :productIds")
    List<ProductQuestion> findQuestionsByProductIds(@Param("productIds") Collection<Long> productIds);

    /**
     * 이번 호출에서 만든 질문 한 행의 시각을 과거로 옮긴다(D-244 D2 α). 미답변 행은 answered_at을 NULL로 유지해 답변 3컬럼 CHECK를 지킨다.
     * 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음. 대상은 id 1행으로 한정한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE product_question SET created_at = :createdAt, updated_at = :updatedAt, "
            + "answered_at = CASE WHEN answered_at IS NULL THEN NULL ELSE :answeredAt END "
            + "WHERE id = :id AND deleted_at IS NULL", nativeQuery = true)
    int shiftTimes(@Param("id") Long id, @Param("createdAt") LocalDateTime createdAt, @Param("updatedAt") LocalDateTime updatedAt,
            @Param("answeredAt") LocalDateTime answeredAt);

    // ---------- 리뷰(D-245) ----------

    /** 데모 구매자(데모 도메인 · BUYER · 탈퇴 전 · 미삭제)의 공개 리뷰 수(숨김 제외 · 삭제분은 엔티티 제한으로 제외). */
    @Query("SELECT COUNT(r) FROM Review r, User u WHERE u.id = r.buyerId AND r.status = :visible AND u.email LIKE :emailPattern "
            + "AND u.withdrawnAt IS NULL AND EXISTS (SELECT 1 FROM UserRole ur WHERE ur.userId = u.id AND ur.role.code = :buyerRole)")
    long countDemoPublicReviews(@Param("emailPattern") String emailPattern, @Param("buyerRole") RoleCode buyerRole,
            @Param("visible") ReviewStatus visible);

    /**
     * 리뷰 후보 품목: 데모 구매자 본인 주문 · 구매확정 · 데모 상호 셀러 상품(삭제된 사용자·상품·셀러는 엔티티 제한으로 제외) · 품목 id 순.
     * 리뷰 이력(삭제 포함)은 여기서 거르지 않는다 — Review 엔티티 제한이 삭제분을 가리므로 {@link #findReviewedOrderItemIds}로 따로 거른다.
     */
    @Query("SELECT oi.id AS orderItemId, oi.publicId AS orderItemPublicId, oi.productId AS productId, p.name AS productName, "
            + "p.categoryId AS categoryId, u.id AS buyerId, u.email AS buyerEmail, oi.confirmedAt AS confirmedAt "
            + "FROM OrderItem oi JOIN oi.order o, User u, Product p, Seller s "
            + "WHERE u.id = o.buyerId AND p.id = oi.productId AND s.id = p.sellerId AND oi.itemStatus = :confirmed "
            + "AND oi.confirmedAt IS NOT NULL AND u.email LIKE :emailPattern AND u.withdrawnAt IS NULL "
            + "AND EXISTS (SELECT 1 FROM UserRole ur WHERE ur.userId = u.id AND ur.role.code = :buyerRole) "
            + "AND s.companyName LIKE :sellerPattern ORDER BY oi.id")
    List<DemoReviewCandidateRow> findReviewCandidates(@Param("emailPattern") String emailPattern, @Param("buyerRole") RoleCode buyerRole,
            @Param("confirmed") OrderItemStatus confirmed, @Param("sellerPattern") String sellerPattern);

    /** 리뷰 이력(삭제 포함)이 있는 품목 id. 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음. */
    @Query(value = "SELECT order_item_id FROM review WHERE order_item_id IN (:orderItemIds)", nativeQuery = true)
    List<Long> findReviewedOrderItemIds(@Param("orderItemIds") Collection<Long> orderItemIds);

    /** 상품들의 기존 리뷰 본문(삭제분은 엔티티 제한으로 제외). */
    @Query("SELECT r.productId AS productId, r.content AS content FROM Review r WHERE r.productId IN :productIds")
    List<DemoReviewContentRow> findReviewContentsByProductIds(@Param("productIds") Collection<Long> productIds);

    /**
     * 이번 호출에서 만든 리뷰 한 행의 작성 시각을 과거로 옮긴다(D-245). 모든 변수는 :name 바인딩 사용, SQL injection 위험 없음. 대상은 id 1행.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE review SET created_at = :writtenAt, updated_at = :writtenAt WHERE id = :id AND deleted_at IS NULL",
            nativeQuery = true)
    int shiftReviewTime(@Param("id") Long id, @Param("writtenAt") LocalDateTime writtenAt);
}
