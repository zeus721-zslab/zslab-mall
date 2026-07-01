package com.zslab.mall.inventory.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.observability.EventMetricsRecorder;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ClaimCompleted(E9) → Inventory 복구/교환 핸들러(Track 17 PR-B·D-101 §3·§5). {@link ClaimCompleted}를 소비해 claimId로
 * Claim을 재조회하고(D-101 §8 β·record 무복제), orderItemId로 OrderItem을 재조회해 variant_id·quantity를 도출한 뒤 type별로
 * 분기한다: CANCEL·RETURN은 {@link InventoryService#restoreStock}(실물 복구), EXCHANGE는 {@link InventoryService#exchange}
 * (회수분 복구 + 교환품 신규 확정·동일 variant 재사용·Claim에 newVariantId 부재·recon §16.10).
 *
 * <p><b>실행 시점(D-101 §3·D-75)</b>: {@code @TransactionalEventListener(AFTER_COMMIT)} + {@code REQUIRES_NEW}로 클레임 종결
 * 커밋 후 별도 트랜잭션에서 복구한다.
 *
 * <p><b>이중 방어(D-100 Q1 γ·D-101 §6 갱신)</b>: 1차 핸들러 가드 = {@link InventoryHistoryRepository#existsByReferenceTypeAndReferenceId}
 * ("claim", claimId)가 true면 skip(멱등). 형제 AFTER_COMMIT 핸들러({@code ClaimCompletedHandler})가 OrderItem을 종결 상태로
 * 전이하는 실행 순서와 무관하게(그래서 item_status 기반 가드는 부적합) History 존재만으로 재처리를 차단한다. 2차 도메인 가드 =
 * restoreStock/exchange 내부 도메인 불변조건 위반 시 throw.
 *
 * <p><b>실패 격리(D-100 Q6 β·Q4 β′)</b>: 복구 실패는 6 표준키 structured log 1줄 + zslab.event.failed 계측 후 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryClaimCompletedHandler {

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;
    private final InventoryHistoryRepository inventoryHistoryRepository;
    private final EventMetricsRecorder eventMetricsRecorder;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(ClaimCompleted event) {
        try {
            // 1차 가드(멱등·순서 독립): 동일 claim으로 이미 복구/교환 이력이 있으면 재처리 skip
            if (inventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", event.claimId())) {
                log.info("[Inventory] event=ClaimCompleted target_id={} action=skip reason=history_exists", event.claimId());
                return;
            }
            Claim claim = claimRepository.findById(event.claimId()).orElse(null);
            if (claim == null) {
                log.warn("[Inventory] event=ClaimCompleted target_id={} action=skip reason=claim_not_found", event.claimId());
                return;
            }
            OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId()).orElse(null);
            if (orderItem == null) {
                log.warn("[Inventory] event=ClaimCompleted target_id={} action=skip reason=order_item_not_found orderItemId={}",
                        event.claimId(), claim.getOrderItemId());
                return;
            }
            Long variantId = orderItem.getVariantId();
            int qty = orderItem.getQuantity();
            switch (claim.getType()) {
                case CANCEL -> inventoryService.restoreStock(
                        variantId, qty, InventoryHistoryChangeType.CANCEL, "claim", event.claimId());
                case RETURN -> inventoryService.restoreStock(
                        variantId, qty, InventoryHistoryChangeType.RETURN, "claim", event.claimId());
                // EXCHANGE: 동일 variant 회수+재발송(Claim newVariantId 부재·recon §16.10)
                case EXCHANGE -> inventoryService.exchange(variantId, qty, variantId, qty, event.claimId());
            }
            log.info("[Inventory] event=ClaimCompleted target_id={} action={} variant_id={} qty={}",
                    event.claimId(), claim.getType(), variantId, qty);
        } catch (RuntimeException exception) {
            log.warn("[Inventory] event={} target_type={} target_id={} action=manual_review correlationId={} handler={}",
                    "ClaimCompleted", "CLAIM", event.claimId(), MDC.get("correlationId"), this.getClass().getSimpleName(), exception);
            eventMetricsRecorder.recordFailed(event.getClass().getSimpleName()); // Q4 β′: zslab.event.failed{event}
        }
    }
}
