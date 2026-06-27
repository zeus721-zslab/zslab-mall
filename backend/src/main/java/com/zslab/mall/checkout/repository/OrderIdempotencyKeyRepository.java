package com.zslab.mall.checkout.repository;

import com.zslab.mall.checkout.entity.OrderIdempotencyKey;
import com.zslab.mall.checkout.entity.OrderIdempotencyKeyId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 멱등성 키 Repository(D-44a·D-52). 복합 PK 조회 + 선점/복구 분기용 명시 조회.
 */
public interface OrderIdempotencyKeyRepository
        extends JpaRepository<OrderIdempotencyKey, OrderIdempotencyKeyId> {

    Optional<OrderIdempotencyKey> findByBuyerIdAndIdempotencyKey(Long buyerId, String idempotencyKey);
}
