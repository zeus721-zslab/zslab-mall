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
 * reserved만 변동하는 reserve·release는 기록하지 않는다. EXCHANGE 흐름({@link #exchange})은 회수분 복구·신규분 확정을
 * 단일 트랜잭션에서 처리하며 InventoryHistory 2행(RETURN·ORDER)을 기록한다(D-101 §5 갱신 α).
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

    /**
     * 교환(EXCHANGE)을 처리한다(E9 ClaimCompleted·EXCHANGE 소비 진입점). 회수분은 실물 복구(RETURN)하고, 교환품 신규 발송은
     * 예약 후 즉시 확정하는 2단계 패턴(α·D-101 §5 갱신)으로 차감(ORDER)한다. commitReservation INV-3 가드를 자연 흡수하며
     * (선행 reserve가 quantityReserved를 먼저 채움), §7 InventoryHistoryChangeType.ADJUST 명명 충돌을 회피한다(기조 4 정합).
     * 회수·신규 2 도메인 행위와 InventoryHistory 2행(RETURN·ORDER)을 단일 DB 트랜잭션에서 원자적으로 처리한다
     * (D-08 [갱신 Track17]·M-12·부분 실패 시 자연 롤백).
     *
     * @throws InventoryInvariantViolationException 회수·신규 variant의 Inventory가 없거나 도메인 불변조건(INV-1)을 위반할 때
     */
    public void exchange(Long returnVariantId, int returnQty, Long newVariantId, int newQty, Long claimId) {
        Inventory returnInventory = inventoryRepository.findByVariantIdForUpdate(returnVariantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + returnVariantId));
        returnInventory.restoreStock(returnQty);
        inventoryHistoryRepository.save(
                InventoryHistory.create(returnInventory, InventoryHistoryChangeType.RETURN, returnQty, "claim", claimId, null));

        Inventory newInventory = inventoryRepository.findByVariantIdForUpdate(newVariantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + newVariantId));
        newInventory.reserve(newQty);            // α 1단계: 예약(quantityReserved 선증가)
        newInventory.commitReservation(newQty);  // α 2단계: 확정(reserved·on_hand 동시 차감)
        inventoryHistoryRepository.save(
                InventoryHistory.create(newInventory, InventoryHistoryChangeType.ORDER, -newQty, "claim", claimId, null));
    }

    /**
     * 운영자 수동 재고를 조정한다(Track 21 D-105 §4·InventoryHistoryChangeType.ADJUST 진입점). on_hand 변동이므로 History를
     * quantity_delta 부호 그대로 기록한다(M-11·D-101 §11). referenceType은 운영자 조정을 표기하는 {@code "admin"}이며 특정
     * 주문·클레임 참조가 없으므로 referenceId는 null이다. 응답 조립(after 수치)을 위해 조정된 Inventory를 반환한다
     * (Controller 재조회 회피·{@code registerExchangeShipmentByAdmin} 엔티티 반환 패턴 정합·recon §7).
     *
     * @throws InventoryInvariantViolationException Inventory 미존재 또는 조정 결과 불변조건(INV-1·INV-4) 위반 시
     * @throws IllegalArgumentException quantityDelta가 0일 때
     */
    public Inventory adjustStock(Long variantId, int quantityDelta, String reason) {
        Inventory inventory = inventoryRepository.findByVariantIdForUpdate(variantId)
                .orElseThrow(() -> new InventoryInvariantViolationException("Inventory 미존재: variantId=" + variantId));
        inventory.adjustStock(quantityDelta);
        inventoryHistoryRepository.save(
                InventoryHistory.create(inventory, InventoryHistoryChangeType.ADJUST, quantityDelta, "admin", null, reason));
        return inventory;
    }
}
