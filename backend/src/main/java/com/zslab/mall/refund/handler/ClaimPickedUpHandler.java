package com.zslab.mall.refund.handler;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimPickedUp;
import com.zslab.mall.notification.service.NotificationService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.refund.service.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 클레임 수거 확인 → 환불 자동 트리거 핸들러(Track 14 PR-1·D-98 Q2·Q5). {@link ClaimPickedUp}(E11)을 소비해 RETURN 클레임
 * 한정으로 {@link RefundService#initiate}를 자동 호출한다. CANCEL은 승인 시점에 환불되고(ClaimApprovedHandler),
 * EXCHANGE는 Refund를 경유하지 않으므로(refundAmount==0) 본 핸들러 대상이 아니다.
 *
 * <p><b>위치(D-94 Q1 α 준용)</b>: 반응 도메인(Refund) 패키지에 둔다. {@code refund/handler/ClaimApprovedHandler}(CANCEL
 * 자동 환불)와 1:1 대칭이며 {@code claim} 패키지로의 {@code refund} 의존 역유입을 피한다.
 *
 * <p><b>실행 시점(D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로 수거 확인 커밋 후
 * 별도 트랜잭션에서 Refund를 생성한다(ClaimApprovedHandler 패턴 1:1).
 *
 * <p><b>amount 산정·멱등(D-94 Q7·Q6 상속)</b>: {@code ClaimPickedUp.orderItemId}로 OrderItem을 조회해 {@code totalPrice}를
 * 환불 금액으로 전달한다. 동일 claimId 활성 Refund 중복 차단은 {@link RefundService#initiate} 내부 게이트가 담당한다.
 *
 * <p><b>실패 보상(D-96 Q3 1:1)</b>: {@code initiate}의 예외는 핸들러 밖으로 전파하지 않고
 * {@code NotificationService.recordRefundFailed}로 운영 알림을 적재한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimPickedUpHandler {

    private final RefundService refundService;
    private final OrderItemRepository orderItemRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimPickedUp event) {
        if (event.claimType() != ClaimType.RETURN) {
            // CANCEL은 승인 시점 환불(ClaimApprovedHandler)·EXCHANGE는 Refund 미경유 → 수거 후 환불 미대상
            log.info("[Refund] ClaimPickedUp 수신·type={} → 자동 환불 미대상: claimId={}", event.claimType(), event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Refund] ClaimPickedUp 소비·주문 품목 미발견 → 자동 환불 건너뜀: orderItemId={}", event.orderItemId());
            return;
        }
        try {
            refundService.initiate(event.claimId(), orderItem.getTotalPrice());
        } catch (RuntimeException exception) {
            // PG 장애·도메인 위반은 핸들러 밖으로 전파하지 않는다(Claim 환원 없음·재 initiate 허용·D-94 Q8·D-96 Q3).
            notificationService.recordRefundFailed(event);
        }
    }
}
