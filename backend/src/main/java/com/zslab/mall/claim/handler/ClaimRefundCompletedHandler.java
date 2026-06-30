package com.zslab.mall.claim.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.claim.service.ClaimService;
import com.zslab.mall.refund.event.RefundCompleted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 환불 완료 이벤트의 Claim 종결 소비 핸들러(Track 5·expected-spec §7·D-69). {@link RefundCompleted}를 받아
 * Claim.type=CANCEL이고 APPROVED인 클레임을 COMPLETED로 전이한다.
 *
 * <p><b>실행 시점(D-69·CR-06)</b>: {@code @TransactionalEventListener(phase = AFTER_COMMIT)}로 Refund UPDATE 커밋 후 진입한다.
 * AFTER_COMMIT 시점은 원 트랜잭션이 이미 완료된 상태라 기본 전파(REQUIRES)는 쓰기가 커밋되지 않는다 → {@code REQUIRES_NEW}로
 * 별도 트랜잭션을 명시한다(D-69 "각자 별도 트랜잭션"·D-75). 부분 실패가 허용되며(재처리 가능) 핸들러는 자체 멱등성을 보장한다.
 *
 * <p><b>type 분기(D-98 Q4·Q2)</b>: CANCEL·RETURN은 Refund.COMPLETED 콜백으로 Claim.COMPLETED 전이한다(RETURN은
 * 수거 확인 후 ClaimPickedUpHandler가 환불을 트리거함). EXCHANGE는 Refund를 경유하지 않으므로(refundAmount==0·
 * Refund 미생성) 본 핸들러 미전이이며 ExchangeDeliveryCompletedHandler(PR-2)가 종결한다.
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefundCompleted(RefundCompleted event) {
        Claim claim = claimRepository.findById(event.claimId()).orElse(null);
        if (claim == null) {
            log.warn("[Claim] RefundCompleted 소비·클레임 미발견: claimId={}", event.claimId());
            return;
        }
        if (claim.getType() == ClaimType.EXCHANGE) {
            // EXCHANGE는 Refund 미경유(refundAmount==0)·ExchangeDeliveryCompletedHandler(PR-2)가 종결
            log.info("[Claim] RefundCompleted 수신·type=EXCHANGE → 본 핸들러 미전이: claimId={}", event.claimId());
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
