package com.zslab.mall.cart.repository;

import com.zslab.mall.cart.entity.CartItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장바구니 품목 Repository.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** 동일 buyer의 동일 variant 담김 여부 조회(M1α 수량 누적·중복 담기 판정). */
    Optional<CartItem> findByUserIdAndVariantId(Long userId, Long variantId);

    /** 장바구니 결제 대상 = 로그인 buyer의 selected=true 품목(Track 41 β·CartCheckoutService 조회·selected Boolean 파생 쿼리). */
    List<CartItem> findByUserIdAndSelectedTrue(Long userId);
}
