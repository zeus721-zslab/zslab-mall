package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.refund.event.RefundCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 환불 완료 이벤트의 Claim 종결 소비 핸들러(Track 5·expected-spec §7·D-69). {@link RefundCompleted}를 받아
 * Claim.type=CANCEL이고 APPROVED인 클레임을 COMPLETED로 전이한다.
 *
 * <p><b>실행 시점(D-172·외부 검토 A 동기화)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션과 같은 TX에서 실행되며 예외는 그대로
 * 전파돼 발행 TX(클레임 전이·환불 콜백 등)를 함께 롤백한다(구 AFTER_COMMIT + REQUIRES_NEW + skip 폐기·후속 처리 유실 방지). 대상 행 미발견은
 * 데이터 불일치라 {@link IllegalStateException}으로 전파하고, 이미 목표 상태인 경우만 멱등 no-op이다.
 * 발행처는 {@code RefundService.handleCallback}(SUCCESS) TX이며, 본 핸들러가 호출하는 {@code markCompleted}의 {@code ClaimCompleted}도
 * 같은 TX에서 동기 소비된다(품목 종결·재고 복구). 구 "각자 별도 트랜잭션"(D-69) 방식은 D-172로 폐기.
 *
 * <p><b>type 분기(D-98 Q4·Q2·D-115 결정3)</b>: CANCEL·RETURN은 Refund.COMPLETED 콜백으로 Claim.COMPLETED 전이한다(RETURN은
 * 수거 확인 후 ClaimPickedUpHandler가 환불을 트리거함). EXCHANGE는 차액 발생 시(refundAmount&gt;0) Refund.COMPLETED가
 * 종결 조건(3)이므로 {@code ClaimService.tryCompleteExchange} 수렴 판정을 시도한다. 차액 없는 교환(refundAmount==0)은 Refund
 * 미생성으로 본 이벤트가 도착하지 않으며 ExchangeDeliveryCompletedHandler가 배송 완료로 종결한다.
 */
@Slf4j
@Component
public class ClaimRefundCompletedHandler {

    private final ClaimRepository claimRepository;
    private final ClaimService claimService;

    public ClaimRefundCompletedHandler(ClaimRepository claimRepository, ClaimService claimService) {
        this.claimRepository = claimRepository;
        this.claimService = claimService;
    }

    @EventListener
    public void onRefundCompleted(RefundCompleted event) {
        Claim claim = claimRepository.findById(event.claimId())
                .orElseThrow(() -> new IllegalStateException("RefundCompleted 소비·클레임 미발견: claimId=" + event.claimId()));
        if (claim.getType() == ClaimType.EXCHANGE) {
            // Track 83 D-177: 교환은 환불을 경유하지 않는다(차액 환불 D-115 폐기). 관리자 수동 환불이 붙어도 교환 종결은 배송완료 경로만.
            log.warn("[Claim] RefundCompleted 수신·type=EXCHANGE → 종결 전이 비대상·skip: claimId={}", event.claimId());
            return;
        }
        if (claim.getStatus() != ClaimStatus.APPROVED) {
            // 멱등(이미 COMPLETED)·비APPROVED 안전 차단 — 리스너 예외로 인한 잡음 방지
            log.info("[Claim] 클레임 상태={} → 종결 전이 건너뜀: claimId={}", claim.getStatus(), event.claimId());
            return;
        }
        claimService.markCompleted(event.claimId());
    }
}
