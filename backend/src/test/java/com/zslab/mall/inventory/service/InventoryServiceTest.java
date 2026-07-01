package com.zslab.mall.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.entity.InventoryHistory;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import com.zslab.mall.inventory.repository.InventoryHistoryRepository;
import com.zslab.mall.inventory.repository.InventoryRepository;
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
 * {@link InventoryService} 단위 검증(Mockito). 비관적 락 조회·도메인 행위 위임·InventoryHistory 기록 책임
 * (M-11·D-101 §11: commitReservation·restoreStock만 기록)을 8 케이스로 커버한다.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final Long VARIANT_ID = 1L;
    private static final String REFERENCE_TYPE = "order";
    private static final Long REFERENCE_ID = 500L;

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryHistoryRepository inventoryHistoryRepository;
    @InjectMocks
    private InventoryService inventoryService;

    private Inventory inventory(int onHand, int reserved, int available) {
        // Inventory 무인자 생성자가 protected(다른 패키지)이므로 BeanUtils로 접근 가능화 후 인스턴스화
        Inventory inventory = BeanUtils.instantiateClass(Inventory.class);
        ReflectionTestUtils.setField(inventory, "id", 10L);
        ReflectionTestUtils.setField(inventory, "variantId", VARIANT_ID);
        ReflectionTestUtils.setField(inventory, "quantityOnHand", onHand);
        ReflectionTestUtils.setField(inventory, "quantityReserved", reserved);
        ReflectionTestUtils.setField(inventory, "quantityAvailable", available);
        return inventory;
    }

    @Test
    @DisplayName("reserve: 정상 → reserved 증가·History 미기록(M-11)")
    void reserve_success_noHistory() {
        Inventory inventory = inventory(10, 2, 8);
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.of(inventory));

        inventoryService.reserve(VARIANT_ID, 3);

        assertThat(inventory.getQuantityReserved()).isEqualTo(5);
        assertThat(inventory.getQuantityAvailable()).isEqualTo(5);
        verify(inventoryHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("reserve: Inventory 미존재 → InventoryInvariantViolationException")
    void reserve_notFound_throws() {
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.reserve(VARIANT_ID, 3))
                .isInstanceOf(InventoryInvariantViolationException.class)
                .hasMessageContaining("Inventory 미존재");
        verify(inventoryHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("release: 정상 → reserved 감소·History 미기록(M-11)")
    void release_success_noHistory() {
        Inventory inventory = inventory(10, 5, 5);
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.of(inventory));

        inventoryService.release(VARIANT_ID, 3);

        assertThat(inventory.getQuantityReserved()).isEqualTo(2);
        verify(inventoryHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("commitReservation: 정상 → History ORDER·delta=-qty·reference 인자 정합")
    void commit_success_historyOrder() {
        Inventory inventory = inventory(10, 5, 5);
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.of(inventory));

        inventoryService.commitReservation(VARIANT_ID, 3, REFERENCE_TYPE, REFERENCE_ID);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(7);
        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository).save(captor.capture());
        InventoryHistory saved = captor.getValue();
        assertThat(saved.getChangeType()).isEqualTo(InventoryHistoryChangeType.ORDER);
        assertThat(saved.getQuantityDelta()).isEqualTo(-3);
        assertThat(saved.getReferenceType()).isEqualTo(REFERENCE_TYPE);
        assertThat(saved.getReferenceId()).isEqualTo(REFERENCE_ID);
        assertThat(saved.getInventory()).isSameAs(inventory);
    }

    @Test
    @DisplayName("restoreStock: type=CANCEL → onHand 증가·History CANCEL·delta=+qty")
    void restore_cancel_historyCancel() {
        Inventory inventory = inventory(7, 2, 5);
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.of(inventory));

        inventoryService.restoreStock(VARIANT_ID, 3, InventoryHistoryChangeType.CANCEL, "claim", REFERENCE_ID);

        assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository).save(captor.capture());
        InventoryHistory saved = captor.getValue();
        assertThat(saved.getChangeType()).isEqualTo(InventoryHistoryChangeType.CANCEL);
        assertThat(saved.getQuantityDelta()).isEqualTo(3);
    }

    @Test
    @DisplayName("restoreStock: type=RETURN → History RETURN 기록")
    void restore_return_historyReturn() {
        Inventory inventory = inventory(7, 2, 5);
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.of(inventory));

        inventoryService.restoreStock(VARIANT_ID, 3, InventoryHistoryChangeType.RETURN, "claim", REFERENCE_ID);

        ArgumentCaptor<InventoryHistory> captor = ArgumentCaptor.forClass(InventoryHistory.class);
        verify(inventoryHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getChangeType()).isEqualTo(InventoryHistoryChangeType.RETURN);
    }

    @Test
    @DisplayName("restoreStock: type=ADJUST → IllegalArgumentException(CANCEL/RETURN 외 거부)")
    void restore_adjust_throws() {
        assertThatThrownBy(() ->
                inventoryService.restoreStock(VARIANT_ID, 3, InventoryHistoryChangeType.ADJUST, "claim", REFERENCE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANCEL 또는 RETURN");
        verify(inventoryHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("restoreStock: Inventory 미존재 → InventoryInvariantViolationException")
    void restore_notFound_throws() {
        when(inventoryRepository.findByVariantIdForUpdate(VARIANT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                inventoryService.restoreStock(VARIANT_ID, 3, InventoryHistoryChangeType.CANCEL, "claim", REFERENCE_ID))
                .isInstanceOf(InventoryInvariantViolationException.class);
        verify(inventoryHistoryRepository, never()).save(any());
    }
}
