package com.zslab.mall.payment.command;

import com.zslab.mall.payment.enums.CallbackType;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * PG 콜백 처리 명령(D-27·D-34). {@code PaymentService.handleCallback} 입력이다.
 *
 * <p>HttpServletRequest·HttpHeaders 등 HTTP 타입을 담지 않는다(D-27 — HTTP 책임은 Controller, 도메인 책임은 Service 분리).
 * Controller가 HTTP 요청을 본 명령으로 변환해 넘긴다.
 *
 * <p>결제 행 식별은 {@code paymentAttemptKey}(1차 키·D-35), {@code pgTid}는 보조 검증·중복 INSERT 차단용(PAY-3b·D-31)이다.
 *
 * @param provider           PG사 식별자(pg_provider)
 * @param callbackType       콜백 타입(SUCCESS·FAILURE·CANCEL)
 * @param paymentAttemptKey  결제 시도 식별자(pat_)·행 매핑 1차 키
 * @param pgTid              PG 거래 ID. 실패·취소 콜백 등 미발급 케이스에서는 null 가능
 * @param occurredAt         콜백 발생 시각. 이벤트 occurredAt·paid_at으로 사용
 * @param metadata           PG 부가 정보(가공 없이 보관·현 트랙 미사용 필드 허용)
 */
public record PaymentCallbackCommand(
        String provider,
        CallbackType callbackType,
        String paymentAttemptKey,
        String pgTid,
        LocalDateTime occurredAt,
        Map<String, String> metadata) {
}
