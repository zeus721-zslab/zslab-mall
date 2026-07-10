package com.zslab.mall.order.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.common.serialization.KstOffsetDeserializer;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.payment.entity.Payment;
import java.time.LocalDateTime;

/**
 * 체크아웃·재결제 공용 응답(§7·D-43). 의미 중심은 payment(재결제는 Order 변경 아님). 주문 본문(seller 그룹)은 미포함(§7 명시 필드·#5).
 * null 필드는 §15 NON_NULL로 생략된다(성공 시 next 생략·실패 시 payment.publicId/redirectUrl/expiresAt 생략).
 */
public record CheckoutResponse(PaymentView payment, NextActions next) {

    /** Payment 직접 속성(PG 발급·서버 상태 canonical·§7). */
    public record PaymentView(
            String publicId,
            StatusView status,
            String redirectUrl,
            @JsonSerialize(using = KstOffsetSerializer.class)
            @JsonDeserialize(using = KstOffsetDeserializer.class)
            LocalDateTime expiresAt) {
    }

    /** 클라이언트 다음 행동 안내(UI 편의·canonical source 아님·§7). */
    public record NextActions(String retryPaymentUrl) {
    }

    /** 신규 주문 + 결제 시작 성공(payment.redirectUrl 존재·next 생략). */
    public static CheckoutResponse forNewOrder(Payment payment, String redirectUrl) {
        return success(payment, redirectUrl);
    }

    /** 재결제 성공(동일 구조). */
    public static CheckoutResponse forRetry(Payment payment, String redirectUrl) {
        return success(payment, redirectUrl);
    }

    /** 신규 주문 성공 + 결제 시작 실패(INITIATE_FAILED·payment.publicId 부재·next.retryPaymentUrl 제공·§7). */
    public static CheckoutResponse forNewOrderInitiateFailed(String retryPaymentUrl) {
        PaymentView payment = new PaymentView(null, StatusView.ofCode("INITIATE_FAILED"), null, null);
        return new CheckoutResponse(payment, new NextActions(retryPaymentUrl));
    }

    private static CheckoutResponse success(Payment payment, String redirectUrl) {
        PaymentView view = new PaymentView(
                payment.getPublicId(),
                StatusView.of(payment.getStatus()),
                redirectUrl,
                payment.getExpiresAt());
        return new CheckoutResponse(view, null);
    }
}
