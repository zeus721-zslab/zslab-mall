package com.zslab.mall.payment.service;

import com.zslab.mall.payment.entity.Payment;

/**
 * 결제 시도 생성 결과({@link PaymentService#initiate} 반환). 영속 결제 행과 PG 발급 결제창 URL(redirectUrl)을 함께 전달한다.
 *
 * <p>redirectUrl은 PG가 발급하는 비영속 값(DDL 컬럼 없음)이라 {@link Payment} 엔티티에 두지 않고 본 결과 타입으로 surface한다.
 * CheckoutResponse(§7)의 {@code payment.redirectUrl} 조립에 사용한다.
 */
public record PaymentInitiation(Payment payment, String redirectUrl) {
}
