package com.zslab.mall.payment.gateway;

import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.service.RefundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Mock PG 환불 자동 완료 리스너(Track 80 D-169·C4). {@link MockRefundAccepted}를 받아 실 PG 웹훅 대신 기존 콜백 서비스
 * {@link RefundService#handleCallback}을 SUCCESS로 호출한다. 이후 Refund COMPLETED → Claim COMPLETED → 품목 CANCELLED·재고 복구는
 * 기존 AFTER_COMMIT 경로 그대로다.
 *
 * <p><b>활성 조건</b>: {@link MockPaymentGateway} 빈이 있을 때만 등록된다({@code @ConditionalOnBean}). 실 PG 어댑터로 교체하면
 * Mock 게이트웨이·본 리스너·이벤트가 함께 소멸하며 프로필·프로퍼티 게이트는 두지 않는다(demo 프로필 부재·운영도 Mock PG 사용).
 *
 * <p><b>실행 시점</b>: 발행 TX(RefundService.initiate) 커밋 후 REQUIRES_NEW. 콜백 실패는 Refund를 PENDING으로 남기고 error 로그만
 * 남긴다(요청 TX·승인 흐름에 영향 없음·운영자 수동 initiate/웹훅 재시도 경로 보존). 중복 콜백은 handleCallback의 RFN-3 멱등 no-op이다.
 */
@Slf4j
@Component
@ConditionalOnBean(MockPaymentGateway.class)
public class MockRefundAutoCallbackListener {

    private final RefundService refundService;

    public MockRefundAutoCallbackListener(RefundService refundService) {
        this.refundService = refundService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMockRefundAccepted(MockRefundAccepted event) {
        try {
            refundService.handleCallback(event.pgRefundId(), RefundCallbackStatus.SUCCESS, null);
            log.info("[MockPaymentGateway] 환불 완료 콜백 자동 발생(SUCCESS): pgRefundId={}", event.pgRefundId());
        } catch (RuntimeException exception) {
            // 모의 콜백 실패는 Refund PENDING 유지·승인 흐름 비차단. 운영자 수동 initiate 또는 웹훅 재호출로 복구한다.
            log.error("[MockPaymentGateway] 환불 자동 완료 콜백 실패 → PENDING 유지: pgRefundId={}", event.pgRefundId(), exception);
        }
    }
}
