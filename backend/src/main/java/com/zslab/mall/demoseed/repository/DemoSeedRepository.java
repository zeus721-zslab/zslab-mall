package com.zslab.mall.demoseed.repository;

import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.user.entity.User;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 데모 시더(D-244) 전용 조회·시각 보정. 도메인 저장소에 데모 전용 메서드를 섞지 않으려고 따로 둔다. 쓰기는 도메인 서비스가 하고,
 * 여기서 쓰는 것은 이번 호출에서 만든 질문 행의 시각 보정뿐이다.
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
}
