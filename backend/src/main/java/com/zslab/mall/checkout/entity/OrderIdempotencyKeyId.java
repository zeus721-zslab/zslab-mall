package com.zslab.mall.checkout.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link OrderIdempotencyKey} 복합 PK(buyer_id, idempotency_key)·@IdClass 식별자.
 * 필드명·타입은 엔티티의 {@code @Id} 필드(buyerId·idempotencyKey)와 1:1 일치해야 한다.
 */
public class OrderIdempotencyKeyId implements Serializable {

    private Long buyerId;
    private String idempotencyKey;

    protected OrderIdempotencyKeyId() {
    }

    public OrderIdempotencyKeyId(Long buyerId, String idempotencyKey) {
        this.buyerId = buyerId;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OrderIdempotencyKeyId that)) {
            return false;
        }
        return Objects.equals(buyerId, that.buyerId) && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(buyerId, idempotencyKey);
    }
}
