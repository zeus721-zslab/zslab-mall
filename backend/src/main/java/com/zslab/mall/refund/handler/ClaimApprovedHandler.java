package com.zslab.mall.refund.handler;

import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.event.ClaimApproved;
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
 * 클레임 승인 → 환불 자동 트리거 핸들러(Track 11·D-94 Q1·Q7·Q8). {@link ClaimApproved}를 소비해 CANCEL 클레임 한정으로
 * {@link RefundService#initiate}를 자동 호출한다. D-87 Q3 → D-90 Q2 → D-92 Q8 → D-93 Q9로 4회 연속 carry-over된
 * "ClaimApproved → Refund 자동 변환"의 forward 트리거 결손을 본 핸들러가 종결한다.
 *
 * <p><b>위치(D-94 Q1 α)</b>: 반응 도메인(Refund) 패키지에 둔다. RefundCompleted 소비자가 {@code claim/handler}·
 * {@code payment/handler}에 반응 도메인별로 분산된 패턴과 1:1 대칭이며, {@code claim} 패키지로의 {@code refund} 의존 역유입을 피한다.
 *
 * <p><b>실행 시점(D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로 Claim 승인 커밋 후
 * 별도 트랜잭션에서 Refund를 생성한다(D-01 Aggregate 간 이벤트 경유·ClaimRequestedHandler 패턴 준용).
 *
 * <p><b>amount 산정(D-94 Q7 α)</b>: {@code ClaimApproved.orderItemId}로 OrderItem을 조회해 {@code totalPrice}를 환불 금액으로
 * 전달한다. CANCEL은 OrderItem 1:1 전건 환불이며 배송비 환불 정책은 본 범위 밖이다(부분환불·배송비 도입 시 재정의·D-94 Q7 박제).
 *
 * <p><b>멱등(D-94 Q6)</b>: 동일 claimId 활성 Refund 중복 차단은 {@link RefundService#initiate} 내부 게이트가 담당한다.
 * 본 핸들러는 가드를 중복하지 않고 위임한다.
 *
 * <p><b>실패 보상(D-94 Q8 α·D-96 흡수)</b>: {@code initiate}의 예외(PG 장애·도메인 위반)는 Claim 상태를 환원하지 않는다(CLM-3·
 * FAILED는 승인 취소가 아님·RFN-2 재시도 허용). 운영 가시성은 D-94 Q8의 structured log 1줄에서 D-96 후속 PR로 NotificationLog
 * 적재(NotificationService.recordRefundFailed)로 전환되었다. Micrometer 카운터는 미도입(Track 13+ Observability 이연).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimApprovedHandler {

    private final RefundService refundService;
    private final OrderItemRepository orderItemRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimApproved event) {
        if (event.claimType() != ClaimType.CANCEL) {
            // RETURN·EXCHANGE는 수거/교환출고 등 추가 단계가 필요해 자동 환불 대상이 아니다(Track 12+ 소관).
            log.info("[Refund] ClaimApproved 수신·type={} → 자동 환불 미대상: claimId={}", event.claimType(), event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Refund] ClaimApproved 소비·주문 품목 미발견 → 자동 환불 건너뜀: orderItemId={}", event.orderItemId());
            return;
        }
        try {
            refundService.initiate(event.claimId(), orderItem.getTotalPrice());
        } catch (RuntimeException exception) {
            // PG 장애·도메인 위반은 핸들러 밖으로 전파하지 않는다(Claim 환원 없음·운영자/Job 재 initiate 허용·D-94 Q8).
            // D-96: structured log → NotificationLog 적재 전환. NotificationService 내부 try-catch + skip+warn 자체 보유로 외부 catch 불필요.
            notificationService.recordRefundFailed(event);
        }
    }
}
