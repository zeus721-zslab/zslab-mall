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
 * {@link InventoryService#commitExchange} 단위 검증(Mockito·Track 83 D-177 결정 4·8). 승인 시 예약된 교환 옵션 확정(ORDER −qty)·
 * 검수 재입고(restock)면 원 옵션 복구(RETURN +qty)·History 기록·variant 오름차순 잠금·실패 경로를 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceExchangeTest {

    private static final Long ORIGINAL_VARIANT_ID = 1L;
    private static final Long EXCHANGE_VARIANT_ID = 2L;
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
    @DisplayName("commitExchange restock=true: 교환 옵션 commit(reserved·on_hand −qty)·원 옵션 restoreStock(+qty)·History ORDER·RETURN 2행·원 variant(작은 id) 먼저 잠금")
    void commitExchange_restock_twoHistoryRows() {
        Inventory original = inventory(ORIGINAL_VARIANT_ID, 5, 0, 5);
        Inventory exchange = inventory(EXCHANGE_VARIANT_ID, 10, 4, 6); // 승인 시 reserve(4) 반영 상태
        when(inventoryRepository.findByVariantIdForUpdate(ORIGINAL_VARIANT_ID)).thenReturn(Optional.of(original));
        when(inventoryRepository.findByVariantIdForUpdate(EXCHANGE_VARIANT_ID)).thenReturn(Optional.of(exchange));

        inventoryService.commitExchange(EXCHANGE_VARIANT_ID, ORIGINAL_VARIANT_ID, 4, true, CLAIM_ID);

        assertThat(exchange.getQuantityOnHand()).isEqualTo(6);
        assertThat(exchange.getQuantityReserved()).isEqualTo(0);
        assertThat(exchange.getQuantityAvailable()).isEqualTo(6);
        assertThat(original.getQuantityOnHand()).isEqualTo(9);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(inventoryRepository);
        inOrder.verify(inventoryRepository).findByVariantIdForUpdate(ORIGINAL_VARIANT_ID);
        inOrder.verify(inventoryRepository).findByVariantIdForUpdate(EXCHANGE_VARIANT_ID);

        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository, times(2)).save(captor.capture());
        List<InventoryHistory> saved = captor.getAllValues();
        assertThat(saved.get(0).getChangeType()).isEqualTo(InventoryHistoryChangeType.ORDER);
        assertThat(saved.get(0).getQuantityDelta()).isEqualTo(-4);
        assertThat(saved.get(0).getReferenceType()).isEqualTo("claim");
        assertThat(saved.get(0).getReferenceId()).isEqualTo(CLAIM_ID);
        assertThat(saved.get(0).getInventory()).isSameAs(exchange);
        assertThat(saved.get(1).getChangeType()).isEqualTo(InventoryHistoryChangeType.RETURN);
        assertThat(saved.get(1).getQuantityDelta()).isEqualTo(4);
        assertThat(saved.get(1).getInventory()).isSameAs(original);
    }

    @Test
    @DisplayName("commitExchange restock=false: 교환 옵션 commit만·원 옵션 불변·History ORDER 1행")
    void commitExchange_noRestock_oneHistoryRow() {
        Inventory original = inventory(ORIGINAL_VARIANT_ID, 5, 0, 5);
        Inventory exchange = inventory(EXCHANGE_VARIANT_ID, 10, 4, 6);
        when(inventoryRepository.findByVariantIdForUpdate(ORIGINAL_VARIANT_ID)).thenReturn(Optional.of(original));
        when(inventoryRepository.findByVariantIdForUpdate(EXCHANGE_VARIANT_ID)).thenReturn(Optional.of(exchange));

        inventoryService.commitExchange(EXCHANGE_VARIANT_ID, ORIGINAL_VARIANT_ID, 4, false, CLAIM_ID);

        assertThat(exchange.getQuantityOnHand()).isEqualTo(6);
        assertThat(original.getQuantityOnHand()).isEqualTo(5);
        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(InventoryHistoryChangeType.ORDER);
    }

    @Test
    @DisplayName("commitExchange 예약 없음(reserved < qty·INV-3) → InventoryInvariantViolationException·History 미기록")
    void commitExchange_withoutReservation_throws() {
        Inventory original = inventory(ORIGINAL_VARIANT_ID, 5, 0, 5);
        Inventory exchange = inventory(EXCHANGE_VARIANT_ID, 10, 0, 10);
        when(inventoryRepository.findByVariantIdForUpdate(ORIGINAL_VARIANT_ID)).thenReturn(Optional.of(original));
        when(inventoryRepository.findByVariantIdForUpdate(EXCHANGE_VARIANT_ID)).thenReturn(Optional.of(exchange));

        assertThatThrownBy(() -> inventoryService.commitExchange(EXCHANGE_VARIANT_ID, ORIGINAL_VARIANT_ID, 4, true, CLAIM_ID))
                .isInstanceOf(InventoryInvariantViolationException.class);
        verify(inventoryHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("commitExchange 교환 variant 미존재 → InventoryInvariantViolationException·History 미기록")
    void commitExchange_exchangeVariantNotFound_throws() {
        Inventory original = inventory(ORIGINAL_VARIANT_ID, 5, 0, 5);
        when(inventoryRepository.findByVariantIdForUpdate(ORIGINAL_VARIANT_ID)).thenReturn(Optional.of(original));
        when(inventoryRepository.findByVariantIdForUpdate(EXCHANGE_VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.commitExchange(EXCHANGE_VARIANT_ID, ORIGINAL_VARIANT_ID, 4, true, CLAIM_ID))
                .isInstanceOf(InventoryInvariantViolationException.class)
                .hasMessageContaining("Inventory 미존재");
        verify(inventoryHistoryRepository, never()).save(any());
    }
}
