package com.zslab.mall.inventory.service;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.entity.InventoryHistory;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 도메인 행위 진입점(Track 17 D-101 §2·§11). 각 메서드는 {@link InventoryRepository#findByVariantIdForUpdate}로
 * 비관적 락을 획득한 뒤 Aggregate 도메인 행위를 호출한다(D-101 §4 α 비관락 일원화).
 *
 * <p>InventoryHistory 기록 책임(M-11·D-101 §11): on_hand 변동이 있는 commitReservation·restoreStock만 기록하고,
 * reserved만 변동하는 reserve·release는 기록하지 않는다. EXCHANGE 흐름은 예약 단계 실측 후 PR-B에서 신설한다(D-101 §5).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository inventoryHistoryRepository;

    /**
     * 재고를 예약한다(E1 OrderPlaced 소비 진입점). on_hand 불변이므로 History를 기록하지 않는다(M-11 정합·D-101 §11).
     */
    public void reserve(Long variantId, int qty) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.reserve(qty);
    }

    /**
     * 재고 예약을 해제한다(E3 PaymentFailed 소비 진입점). on_hand 불변이므로 History를 기록하지 않는다(M-11 정합).
     */
    public void release(Long variantId, int qty) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.release(qty);
    }

    /**
     * 예약분을 실물 차감으로 확정한다(E2 PaymentCompleted 소비 진입점). on_hand 감소이므로 History ORDER를
     * quantity_delta 음수(-qty)로 기록한다(M-11·D-101 §11).
     */
    public void commitReservation(Long variantId, int qty, String referenceType, Long referenceId) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.commitReservation(qty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.ORDER, -qty, referenceType, referenceId, null));
    }

    /**
     * 실물 재고를 복구한다(E9 ClaimCompleted 소비 진입점). CANCEL(결제후취소)·RETURN(반품·교환회수) 유형만 허용하며
     * (D-08 M-12), on_hand 증가이므로 History를 quantity_delta 양수(+qty)로 기록한다(M-11·D-101 §11).
     *
     * @throws IllegalArgumentException type이 CANCEL·RETURN이 아닐 때
     */
    public void restoreStock(Long variantId, int qty, InventoryHistoryChangeType type, String referenceType, Long referenceId) {
        if (type != InventoryHistoryChangeType.CANCEL && type != InventoryHistoryChangeType.RETURN) {
            throw new IllegalArgumentException("restoreStock: type은 CANCEL 또는 RETURN이어야 합니다. type=" + type);
        }
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.restoreStock(qty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, type, qty, referenceType, referenceId, null));
    }
}
