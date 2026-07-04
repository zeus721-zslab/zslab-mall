package com.zslab.mall.cart.repository;

import com.zslab.mall.cart.entity.CartItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장바구니 품목 Repository.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** 동일 buyer의 동일 variant 담김 여부 조회(M1α 수량 누적·중복 담기 판정). */
    Optional<CartItem> findByUserIdAndVariantId(Long userId, Long variantId);
}
