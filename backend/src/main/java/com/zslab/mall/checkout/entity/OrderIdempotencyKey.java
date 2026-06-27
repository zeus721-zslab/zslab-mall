package com.zslab.mall.checkout.entity;

import com.zslab.mall.checkout.enums.IdempotencyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 주문 생성 멱등성 키(D-44a·D-52·내부 기술 테이블). 복합 PK(buyer_id, idempotency_key)·@IdClass.
 *
 * <p><b>{@link Persistable} 구현 이유</b>: 복합 PK는 "id null=신규" 기본 판정이 불가하다(두 키 모두 비-null).
 * {@code newRecord} 플래그로 신규 여부를 명시해 save 시 persist(INSERT)를 강제하고, PK 충돌을 즉시 감지한다
 * (동시 요청 409 분기·D-52). 적재 후·로드 후에는 false로 전환되어 이후 save는 merge(UPDATE)된다.
 *
 * <p>full audit 미적용(created_at·completed_at만 보유)이라 audit 베이스를 상속하지 않고 created_at을 팩토리에서 직접 설정한다.
 */
@Entity
@Table(name = "order_idempotency_key")
@IdClass(OrderIdempotencyKeyId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderIdempotencyKey implements Persistable<OrderIdempotencyKeyId> {

    @Id
    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    @Id
    @Column(name = "idempotency_key", length = 128, nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private IdempotencyStatus status;

    @Column(name = "response_body", columnDefinition = "LONGTEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Getter(AccessLevel.NONE)
    @Transient
    private boolean newRecord = true;

    /** 키 선점(IN_PROGRESS·order_id 미할당)으로 신규 생성한다(D-52 1단계). */
    public static OrderIdempotencyKey startInProgress(Long buyerId, String idempotencyKey, LocalDateTime now) {
        if (buyerId == null || idempotencyKey == null || idempotencyKey.isBlank() || now == null) {
            throw new IllegalArgumentException("멱등성 키 필수값 누락(buyerId·idempotencyKey·now).");
        }
        OrderIdempotencyKey key = new OrderIdempotencyKey();
        key.buyerId = buyerId;
        key.idempotencyKey = idempotencyKey;
        key.status = IdempotencyStatus.IN_PROGRESS;
        key.createdAt = now;
        return key;
    }

    /** 생성된 주문 id를 연결한다(D-52 3단계·재시도 복구 분기 키). */
    public void linkOrder(Long orderId) {
        this.orderId = orderId;
    }

    /** 2xx 성공 응답을 캐싱하고 완료로 전이한다(D-52 5단계·§10). */
    public void complete(String responseBody, LocalDateTime completedAt) {
        this.responseBody = responseBody;
        this.completedAt = completedAt;
        this.status = IdempotencyStatus.COMPLETED;
    }

    @Override
    public OrderIdempotencyKeyId getId() {
        return new OrderIdempotencyKeyId(buyerId, idempotencyKey);
    }

    @Override
    @Transient
    public boolean isNew() {
        return newRecord;
    }

    @PostLoad
    @PrePersist
    void markPersisted() {
        this.newRecord = false;
    }
}
