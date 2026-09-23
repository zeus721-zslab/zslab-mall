package com.zslab.mall.refund.service;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.service.OrderService;
import com.zslab.mall.refund.enums.RefundCallbackStatus;
import com.zslab.mall.refund.repository.RefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 환불 복구 단건 Application Service(D-172·Q2-a·RefundRecoveryScheduler 실행체). 배치 오케스트레이션과 분리된 건별 독립 트랜잭션이다
 * (OrderAutoCancelService 패턴). 멱등은 {@link RefundService#initiate}(클레임 행 락 + 활성 환불 게이트)·{@link RefundService#handleCallback}
 * (환불 행 락 + 종결 no-op)이 보장하므로 본 서비스는 위임만 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundRecoveryService {

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final RefundService refundService;
    private final OrderService orderService;
    private final RefundRepository refundRepository;

    /**
     * 환불 누락 클레임 1건에 환불을 시작한다(자동 핸들러가 실패·유실된 경우). 금액은 자동 핸들러와 동일하게 품목 totalPrice다.
     *
     * <p><b>품목 잔여 없음 제외(Track 104-3a)</b>: 품목 기환불액이 품목 금액에 도달했으면 initiate를 부르지 않는다 — 품목 상한이 422로 막아
     * 배치마다 같은 실패를 되풀이하기 때문이다(다른 클레임의 환불로 이미 품목 금액만큼 나간 경우).
     *
     * @return initiate가 새 환불을 만들었거나 기존 활성 환불을 돌려줬으면 true(둘 다 "환불 존재"로 수렴) · 품목 잔여 없음으로 제외하면 false
     */
    @Transactional
    public boolean recoverMissingRefund(Long claimId) {
        // Track 104-1 D-215(P5): 클레임·품목을 적재하기 전에 주문 쓰기 락을 먼저 잡는다(initiate의 재호출은 이미 쥔 락).
        claimRepository.findOrderIdById(claimId).ifPresent(orderService::lockForWrite);
        Claim claim = claimRepository.findById(claimId)
                .orElseThrow(() -> new IllegalStateException("환불 복구 대상 클레임 미존재: claimId=" + claimId));
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException("환불 복구 대상 품목 미존재: orderItemId=" + claim.getOrderItemId()));
        long itemRefunded = refundRepository.sumRefundedByOrderItemId(orderItem.getId());
        if (orderItem.getTotalPrice() - itemRefunded <= 0) {
            log.warn("[RefundRecovery] 품목 잔여 환불 한도 없음 → 복구 제외 claimId={} orderItemId={} itemTotalPrice={} itemRefunded={}",
                    claimId, orderItem.getId(), orderItem.getTotalPrice(), itemRefunded);
            return false;
        }
        refundService.initiate(claimId, orderItem.getTotalPrice());
        log.info("[RefundRecovery] 환불 누락 복구 initiate claimId={} type={} amount={}", claimId, claim.getType(), orderItem.getTotalPrice());
        return true;
    }

    /** Mock PG 한정: 완료 콜백이 유실된 PENDING 환불에 SUCCESS 콜백을 다시 일으킨다(handleCallback 멱등). */
    @Transactional
    public void replayMockSuccessCallback(String pgRefundId) {
        refundService.handleCallback(pgRefundId, RefundCallbackStatus.SUCCESS, null);
        log.info("[RefundRecovery] Mock 완료 콜백 재발생 pgRefundId={}", pgRefundId);
    }
}
