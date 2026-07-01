package com.zslab.mall.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.entity.InventoryHistory;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.repository.InventoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link InventoryService#exchange} 단위 검증(Mockito·D-101 §5 갱신 α). 회수분 복구(RETURN)·신규분 예약→확정(ORDER)
 * 2단계 패턴과 InventoryHistory 2행 기록·실패 경로를 커버한다.
 *
 * <p><b>"신규 확정 실패" 케이스 부재 사유</b>: α 패턴은 신규분에서 {@code reserve(qty)} 직후 {@code commitReservation(qty)}를
 * 호출한다. reserve 성공은 {@code quantityReserved >= qty}·{@code quantityOnHand >= quantityReserved >= qty}를 보장하므로
 * 직후 commitReservation의 INV-3·INV-4는 도달 불가다. 따라서 실패 케이스는 회수 미존재·신규 미존재·신규 예약(INV-1) 3건으로 구성한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceExchangeTest {

    private static final Long RETURN_VARIANT_ID = 1L;
    private static final Long NEW_VARIANT_ID = 2L;
    private static final Long CLAIM_ID = 700L;

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryHistoryRepository inventoryHistoryRepository;
    @InjectMocks
    private InventoryService inventoryService;

    private Inventory inventory(Long variantId, int onHand, int reserved, int available) {
        Inventory inventory = BeanUtils.instantiateClass(Inventory.class);
        ReflectionTestUtils.setField(inventory, "id", variantId * 10);
        ReflectionTestUtils.setField(inventory, "variantId", variantId);
        ReflectionTestUtils.setField(inventory, "quantityOnHand", onHand);
        ReflectionTestUtils.setField(inventory, "quantityReserved", reserved);
        ReflectionTestUtils.setField(inventory, "quantityAvailable", available);
        return inventory;
    }

    @Test
    @DisplayName("exchange 정상: 회수분 restoreStock(+RETURN)·신규분 reserve→commit(-ORDER)·History 2행")
    void exchange_success_twoHistoryRows() {
        Inventory returnInventory = inventory(RETURN_VARIANT_ID, 5, 0, 5);
        Inventory newInventory = inventory(NEW_VARIANT_ID, 10, 2, 8);
        when(inventoryRepository.findByVariantIdForUpdate(RETURN_VARIANT_ID)).thenReturn(Optional.of(returnInventory));
        when(inventoryRepository.findByVariantIdForUpdate(NEW_VARIANT_ID)).thenReturn(Optional.of(newInventory));

        inventoryService.exchange(RETURN_VARIANT_ID, 3, NEW_VARIANT_ID, 4, CLAIM_ID);

        // 회수분: on_hand 5 → 8
        assertThat(returnInventory.getQuantityOnHand()).isEqualTo(8);
        // 신규분: reserve(4)로 reserved 2→6, commit(4)로 on_hand 10→6·reserved 6→2
        assertThat(newInventory.getQuantityOnHand()).isEqualTo(6);
        assertThat(newInventory.getQuantityReserved()).isEqualTo(2);
        assertThat(newInventory.getQuantityAvailable()).isEqualTo(4);

        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository, times(2)).save(captor.capture());
        List<InventoryHistory> saved = captor.getAllValues();
        // 1행: 회수 RETURN·delta=+3
        assertThat(saved.get(0).getChangeType()).isEqualTo(InventoryHistoryChangeType.RETURN);
        assertThat(saved.get(0).getQuantityDelta()).isEqualTo(3);
        assertThat(saved.get(0).getReferenceType()).isEqualTo("claim");
        assertThat(saved.get(0).getReferenceId()).isEqualTo(CLAIM_ID);
        assertThat(saved.get(0).getInventory()).isSameAs(returnInventory);
        // 2행: 신규 ORDER·delta=-4
        assertThat(saved.get(1).getChangeType()).isEqualTo(InventoryHistoryChangeType.ORDER);
        assertThat(saved.get(1).getQuantityDelta()).isEqualTo(-4);
        assertThat(saved.get(1).getReferenceType()).isEqualTo("claim");
        assertThat(saved.get(1).getInventory()).isSameAs(newInventory);
    }

    @Test
    @DisplayName("exchange 회수 variant 미존재 → InventoryInvariantViolationException·History 미기록")
    void exchange_returnVariantNotFound_throws() {
        when(inventoryRepository.findByVariantIdForUpdate(RETURN_VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.exchange(RETURN_VARIANT_ID, 3, NEW_VARIANT_ID, 4, CLAIM_ID))
                .isInstanceOf(InventoryInvariantViolationException.class)
                .hasMessageContaining("Inventory 미존재");
        verify(inventoryHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("exchange 신규 variant 미존재 → InventoryInvariantViolationException·RETURN 1행만 기록")
    void exchange_newVariantNotFound_throwsAfterReturnHistory() {
        Inventory returnInventory = inventory(RETURN_VARIANT_ID, 5, 0, 5);
        when(inventoryRepository.findByVariantIdForUpdate(RETURN_VARIANT_ID)).thenReturn(Optional.of(returnInventory));
        when(inventoryRepository.findByVariantIdForUpdate(NEW_VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.exchange(RETURN_VARIANT_ID, 3, NEW_VARIANT_ID, 4, CLAIM_ID))
                .isInstanceOf(InventoryInvariantViolationException.class)
                .hasMessageContaining("Inventory 미존재");
        // 회수 RETURN 1행은 저장됨(단일 TX라 실 환경은 롤백·Mockito 단위에선 호출 관찰만)
        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(InventoryHistoryChangeType.RETURN);
    }

    @Test
    @DisplayName("exchange 신규 예약 실패(available 부족·INV-1) → InventoryInvariantViolationException·ORDER 미기록")
    void exchange_newReserveViolatesInv1_throws() {
        Inventory returnInventory = inventory(RETURN_VARIANT_ID, 5, 0, 5);
        Inventory newInventory = inventory(NEW_VARIANT_ID, 1, 0, 1); // available 1 < 신규 4 → reserve INV-1 위반
        when(inventoryRepository.findByVariantIdForUpdate(RETURN_VARIANT_ID)).thenReturn(Optional.of(returnInventory));
        when(inventoryRepository.findByVariantIdForUpdate(NEW_VARIANT_ID)).thenReturn(Optional.of(newInventory));

        assertThatThrownBy(() -> inventoryService.exchange(RETURN_VARIANT_ID, 3, NEW_VARIANT_ID, 4, CLAIM_ID))
                .isInstanceOf(InventoryInvariantViolationException.class)
                .hasMessageContaining("불법 재고 예약");
        // RETURN 1행만·ORDER(delta 음수) 미기록
        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(InventoryHistoryChangeType.RETURN);
    }
}
