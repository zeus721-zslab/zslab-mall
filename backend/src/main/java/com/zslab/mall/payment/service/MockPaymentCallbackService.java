package com.zslab.mall.payment.service;

import com.github.f4b6a3.ulid.UlidCreator;
import com.zslab.mall.order.entity.Order;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.payment.command.PaymentCallbackCommand;
import com.zslab.mall.payment.entity.Payment;
import com.zslab.mall.payment.enums.CallbackType;
import com.zslab.mall.payment.exception.PaymentNotFoundException;
import com.zslab.mall.payment.gateway.PaymentGateway;
import com.zslab.mall.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * mock 결제 콜백 Application Service(Track 93·D-198). 구매자가 자기 주문의 결제 시도에 한해 모의 PG 결과(SUCCESS·FAILURE·CANCEL)를
 * 통지한다. 실 PG 콜백 수신은 {@code PaymentWebhookController}({@code /api/webhooks/payments})가 담당하며 본 서비스는 그 자리를 대체하지
 * 않는다 — gateway가 {@code /api/webhooks}를 외부 차단한 뒤에도 mock 결제 페이지가 인가된 경로로 결제를 진행하기 위한 seam이다.
 *
 * <p><b>인가(404 은닉)</b>: attemptKey 미존재·타인 주문 모두 {@link PaymentNotFoundException}(404)으로 통일해 타인 결제 시도의 존재
 * 여부를 노출하지 않는다({@code PaymentService.initiate} §2·D-42 정합).
 *
 * <p><b>서버 생성 필드</b>: provider는 {@link PaymentGateway#provider()}, pgTid는 SUCCESS에만 {@code mocktid_}+ULID, occurredAt은 서버
 * 현재 시각이다. 콜백 처리(상태 전이·락·이벤트)는 {@link PaymentService#handleCallback}을 그대로 재사용한다(로직 중복 없음).
 */
@Slf4j
@Service
@Transactional
@ConditionalOnProperty(name = "zslab.payment.gateway", havingValue = "mock", matchIfMissing = true)
public class MockPaymentCallbackService {

    /** mock PG 거래 ID prefix(FE가 만들던 값을 서버로 이전·payment.pg_tid VARCHAR(100) 내). */
    private static final String MOCK_TID_PREFIX = "mocktid_";

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;

    public MockPaymentCallbackService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            PaymentGateway paymentGateway,
            PaymentService paymentService) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
        this.paymentService = paymentService;
    }

    /**
     * 구매자 본인 결제 시도에 mock 콜백을 적용한다.
     *
     * @param buyerId      요청자 buyer_id(JWT principal)
     * @param attemptKey   결제 시도 식별자(pat_)
     * @param callbackType 콜백 타입
     * @throws PaymentNotFoundException attemptKey 미존재·주문 미존재·타인 주문(404·정보 노출 회피)
     * @throws com.zslab.mall.payment.exception.InvalidCallbackException 상태 조합 REJECT(422·{@link PaymentService#handleCallback})
     */
    public void handleMockCallback(Long buyerId, String attemptKey, CallbackType callbackType) {
        Payment payment = paymentRepository.findByPaymentAttemptKey(attemptKey)
                .orElseThrow(() -> new PaymentNotFoundException("결제 시도를 찾을 수 없습니다: attemptKey=" + attemptKey));
        Order order = orderRepository.findById(payment.getOrderId())
                .orElseThrow(() -> new PaymentNotFoundException("결제 시도를 찾을 수 없습니다: attemptKey=" + attemptKey));
        if (!order.getBuyerId().equals(buyerId)) {
            log.warn("[MockPaymentCallback] 타인 주문 콜백 차단(404) buyerId={} orderId={} attemptKey={}",
                    buyerId, order.getId(), attemptKey);
            throw new PaymentNotFoundException("결제 시도를 찾을 수 없습니다: attemptKey=" + attemptKey);
        }

        String pgTid = callbackType == CallbackType.SUCCESS
                ? MOCK_TID_PREFIX + UlidCreator.getMonotonicUlid()
                : null;
        paymentService.handleCallback(new PaymentCallbackCommand(
                paymentGateway.provider(), callbackType, attemptKey, pgTid, LocalDateTime.now(), null));
    }
}
