package com.zslab.mall.refund.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.event.ClaimInspectionPassed;
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
 * 반품 검수 합격 → 환불 자동 트리거(Track 81-A D-170·R5). 구 {@code ClaimPickedUpHandler}(수거 확인 시점 환불)를 대체한다 — RETURN 환불은
 * 회수 확인이 아니라 검수 PASS 시점에 개시된다. amount는 {@code OrderItem.totalPrice}(취소·D-94 동일 기준·배송비 차감 없음·R6).
 *
 * <p><b>실행 시점</b>: AFTER_COMMIT + REQUIRES_NEW. 멱등은 {@link RefundService#initiate} 내부 게이트(D-94 Q6).
 * <b>실패 보상</b>: PG 장애·도메인 위반은 전파하지 않고 {@code recordRefundFailed}로 운영 알림만 적재한다(운영자 재 initiate 허용·D-94 Q8).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimInspectionPassedHandler {

    private final RefundService refundService;
    private final OrderItemRepository orderItemRepository;
    private final ClaimRepository claimRepository;
    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimInspectionPassed event) {
        // Track 83 D-177: 교환 검수 PASS는 환불 없이 교환품 발송 대기다(같은 가격·차액 없음). 유형은 클레임 행에서 판정한다.
        Claim claim = claimRepository.findById(event.claimId()).orElse(null);
        if (claim == null) {
            log.warn("[Refund] ClaimInspectionPassed 소비·클레임 미발견 → 자동 환불 건너뜀: claimId={}", event.claimId());
            return;
        }
        if (claim.getType() != ClaimType.RETURN) {
            log.info("[Refund] ClaimInspectionPassed 수신·type={} → 자동 환불 미대상(교환품 발송 대기): claimId={}",
                    claim.getType(), event.claimId());
            return;
        }
        OrderItem orderItem = orderItemRepository.findById(event.orderItemId()).orElse(null);
        if (orderItem == null) {
            log.warn("[Refund] ClaimInspectionPassed 소비·주문 품목 미발견 → 자동 환불 건너뜀: orderItemId={}", event.orderItemId());
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
