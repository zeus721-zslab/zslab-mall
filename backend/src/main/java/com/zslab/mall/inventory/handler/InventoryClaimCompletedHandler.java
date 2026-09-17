package com.zslab.mall.inventory.handler;

import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.event.ClaimCompleted;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.service.InventoryService;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.order.repository.OrderItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * ClaimCompleted(E9) → Inventory 복구/교환 핸들러(Track 17 PR-B·D-101 §3·§5). {@link ClaimCompleted}를 소비해 claimId로
 * Claim을 재조회하고(D-101 §8 β·record 무복제), orderItemId로 OrderItem을 재조회해 variant_id·quantity를 도출한 뒤 type별로
 * 분기한다: CANCEL은 {@link InventoryService#restoreStock}(실물 복구), RETURN은 검수 재입고(claim.restock=true)일 때만 restoreStock(Track 81-A D-170),
 * EXCHANGE는 {@link InventoryService#exchange}
 * (회수분 복구 + 교환품 신규 확정·동일 variant 재사용·Claim에 newVariantId 부재·recon §16.10).
 *
 * <p><b>실행 시점(D-172·외부 검토 A 동기화)</b>: {@code @EventListener} 동기 소비 — 발행 트랜잭션과 같은 TX에서 실행되며 예외는 그대로
 * 전파돼 발행 TX(클레임 전이·환불 콜백 등)를 함께 롤백한다(구 AFTER_COMMIT + REQUIRES_NEW + skip 폐기·후속 처리 유실 방지). 대상 행 미발견은
 * 데이터 불일치라 {@link IllegalStateException}으로 전파하고, 이미 목표 상태인 경우만 멱등 no-op이다.
 * 클레임은 품목 1건(variant 1개)이라 다중 variant 정렬은 없다(다중 품목 도입 시 variantId 오름차순 고정·데드락 회피).
 *
 * <p><b>이중 방어(D-100 Q1 γ·D-101 §6 갱신)</b>: 1차 핸들러 가드 = {@link InventoryHistoryRepository#existsByReferenceTypeAndReferenceId}
 * ("claim", claimId)가 true면 skip(멱등). 형제 AFTER_COMMIT 핸들러({@code ClaimCompletedHandler})가 OrderItem을 종결 상태로
 * 전이하는 실행 순서와 무관하게(그래서 item_status 기반 가드는 부적합) History 존재만으로 재처리를 차단한다. 2차 도메인 가드 =
 * restoreStock/exchange 내부 도메인 불변조건 위반 시 throw.
 *
 * <p><b>실패 전파(D-172·구 D-100 Q6 β 폐기)</b>: 복구 실패(재고 불변조건 위반 등)는 흡수하지 않고 전파해 클레임 종결 TX(환불 콜백)를
 * 롤백한다 — 콜백은 422로 응답하고 PG가 재전송한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryClaimCompletedHandler {

    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;
    private final InventoryHistoryRepository inventoryHistoryRepository;

    @EventListener
    public void handle(ClaimCompleted event) {
        // 1차 가드(멱등·순서 독립): 동일 claim으로 이미 복구/교환 이력이 있으면 재처리 skip
        if (inventoryHistoryRepository.existsByReferenceTypeAndReferenceId("claim", event.claimId())) {
            log.info("[Inventory] event=ClaimCompleted target_id={} action=skip reason=history_exists", event.claimId());
            return;
        }
        Claim claim = claimRepository.findById(event.claimId())
                .orElseThrow(() -> new IllegalStateException("ClaimCompleted 소비·클레임 미발견: claimId=" + event.claimId()));
        OrderItem orderItem = orderItemRepository.findById(claim.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "ClaimCompleted 소비·주문 품목 미발견: orderItemId=" + claim.getOrderItemId()));
        Long variantId = orderItem.getVariantId();
        int qty = orderItem.getQuantity();
        switch (claim.getType()) {
            case CANCEL -> inventoryService.restoreStock(
                    variantId, qty, InventoryHistoryChangeType.CANCEL, "claim", event.claimId());
            case RETURN -> {
                // Track 81-A D-170·R5: 검수 PASS 시 재입고 여부를 검수자가 고른다. 불량 폐기(restock=false)는 재고·history 모두 불변.
                if (!claim.isRestockRequested()) {
                    log.info("[Inventory] event=ClaimCompleted target_id={} action=skip reason=restock_false variant_id={} qty={}",
                            event.claimId(), variantId, qty);
                    return;
                }
                inventoryService.restoreStock(variantId, qty, InventoryHistoryChangeType.RETURN, "claim", event.claimId());
            }
            // EXCHANGE: 동일 variant 회수+재발송(Claim newVariantId 부재·recon §16.10)
            case EXCHANGE -> inventoryService.exchange(variantId, qty, variantId, qty, event.claimId());
        }
        log.info("[Inventory] event=ClaimCompleted target_id={} action={} variant_id={} qty={}",
                event.claimId(), claim.getType(), variantId, qty);
    }
}
