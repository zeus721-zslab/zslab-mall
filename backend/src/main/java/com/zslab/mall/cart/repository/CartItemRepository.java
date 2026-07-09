package com.zslab.mall.cart.repository;

import com.zslab.mall.cart.entity.CartItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장바구니 품목 Repository.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** 동일 buyer의 동일 variant 담김 여부 조회(M1α 수량 누적·중복 담기 판정·내부 variantId 기준·UK 정합). */
    Optional<CartItem> findByUserIdAndVariantId(Long userId, Long variantId);

    /** 외부 대상키(variantPublicId)로 담김 품목 조회(수량변경·selected 토글·비정규화 스냅샷 기준·소유권 userId 스코프). */
    Optional<CartItem> findByUserIdAndVariantPublicId(Long userId, String variantPublicId);

    /** 로그인 buyer의 장바구니 전체 조회(Track 45 GET·selected 전체 토글·userId 스코프 소유권 자동). */
    List<CartItem> findByUserId(Long userId);

    /** 장바구니 결제 대상 = 로그인 buyer의 selected=true 품목(Track 41 β·CartCheckoutService 조회·selected Boolean 파생 쿼리). */
    List<CartItem> findByUserIdAndSelectedTrue(Long userId);

    /**
     * 주문 소비 대상 CartItem을 HARD DELETE한다(Track 41 Phase 4·Track 67 CartPaymentCompletedHandler). buyer 스코프 + 주문된 variantId 집합
     * 매칭 물리삭제(파생 삭제·@Modifying 미도입·D-A γ). 호출부 REQUIRES_NEW 트랜잭션 내에서 실행되며, 대상 부재 시 0을 반환한다.
     *
     * @return 삭제된 행 수
     */
    long deleteByUserIdAndVariantIdIn(Long userId, Collection<Long> variantIds);

    /**
     * 외부 대상키(variantPublicId) 집합으로 buyer 스코프 물리삭제한다(Track 45 삭제·비정규화 스냅샷 기준·단건도 배열 1개).
     * userId 스코프라 타 buyer 항목은 삭제되지 않으며(소유권 자동), dangling(variant soft-delete)도 스냅샷 보유로 삭제 가능하다.
     *
     * @return 삭제된 행 수
     */
    long deleteByUserIdAndVariantPublicIdIn(Long userId, Collection<String> variantPublicIds);
}
