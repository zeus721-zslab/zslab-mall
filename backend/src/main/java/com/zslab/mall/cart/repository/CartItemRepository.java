package com.zslab.mall.cart.repository;

import com.zslab.mall.cart.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 장바구니 품목 Repository.
 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {
}
