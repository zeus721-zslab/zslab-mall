package com.zslab.mall.inventory.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.inventory.exception.InventoryInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link Inventory} 도메인 행위(reserve·release·commitReservation·restoreStock) 및 available 재계산·불변조건
 * (INV-1·INV-3·INV-4) 검증. D-101 §2 정합.
 */
class InventoryTest {

    private static final Long VARIANT_ID = 1L;

    private Inventory newInventory(int onHand, int reserved, int available) {
        Inventory inventory = new Inventory();
        ReflectionTestUtils.setField(inventory, "id", 10L);
        ReflectionTestUtils.setField(inventory, "variantId", VARIANT_ID);
        ReflectionTestUtils.setField(inventory, "quantityOnHand", onHand);
        ReflectionTestUtils.setField(inventory, "quantityReserved", reserved);
        ReflectionTestUtils.setField(inventory, "quantityAvailable", available);
        return inventory;
    }

    @Nested
    @DisplayName("reserve")
    class Reserve {

        @Test
        @DisplayName("정상: reserved 증가·available 재계산")
        void reserve_success() {
            Inventory inventory = newInventory(10, 2, 8);

            inventory.reserve(3);

            assertThat(inventory.getQuantityReserved()).isEqualTo(5);
            assertThat(inventory.getQuantityAvailable()).isEqualTo(5);
            assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
        }

        @Test
        @DisplayName("실패: qty=0 → IllegalArgumentException")
        void reserve_zero_throws() {
            Inventory inventory = newInventory(10, 2, 8);

            assertThatThrownBy(() -> inventory.reserve(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserve");
        }

        @Test
        @DisplayName("실패: qty=-1 → IllegalArgumentException")
        void reserve_negative_throws() {
            Inventory inventory = newInventory(10, 2, 8);

            assertThatThrownBy(() -> inventory.reserve(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: available 부족 → InventoryInvariantViolationException·상태 불변")
        void reserve_overAvailable_throws() {
            Inventory inventory = newInventory(10, 2, 8);

            assertThatThrownBy(() -> inventory.reserve(9))
                    .isInstanceOf(InventoryInvariantViolationException.class)
                    .hasMessageContaining("불법 재고 예약");
            // 위반 시 계산·검증 후 mutate로 상태 보존
            assertThat(inventory.getQuantityReserved()).isEqualTo(2);
            assertThat(inventory.getQuantityAvailable()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("정상: reserved 감소·available 재계산")
        void release_success() {
            Inventory inventory = newInventory(10, 5, 5);

            inventory.release(3);

            assertThat(inventory.getQuantityReserved()).isEqualTo(2);
            assertThat(inventory.getQuantityAvailable()).isEqualTo(8);
        }

        @Test
        @DisplayName("실패: qty=0 → IllegalArgumentException")
        void release_zero_throws() {
            Inventory inventory = newInventory(10, 5, 5);

            assertThatThrownBy(() -> inventory.release(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패: reserved 부족 → InventoryInvariantViolationException")
        void release_overReserved_throws() {
            Inventory inventory = newInventory(10, 5, 5);

            assertThatThrownBy(() -> inventory.release(6))
                    .isInstanceOf(InventoryInvariantViolationException.class)
                    .hasMessageContaining("불법 재고 해제");
        }
    }

    @Nested
    @DisplayName("commitReservation")
    class CommitReservation {

        @Test
        @DisplayName("정상: onHand·reserved 동시 감소·available 재계산")
        void commit_success() {
            Inventory inventory = newInventory(10, 5, 5);

            inventory.commitReservation(3);

            assertThat(inventory.getQuantityOnHand()).isEqualTo(7);
            assertThat(inventory.getQuantityReserved()).isEqualTo(2);
            assertThat(inventory.getQuantityAvailable()).isEqualTo(5);
        }

        @Test
        @DisplayName("실패: reserved 부족 → InventoryInvariantViolationException")
        void commit_overReserved_throws() {
            Inventory inventory = newInventory(10, 2, 8);

            assertThatThrownBy(() -> inventory.commitReservation(3))
                    .isInstanceOf(InventoryInvariantViolationException.class)
                    .hasMessageContaining("예약 부족");
        }

        @Test
        @DisplayName("실패: onHand 부족 → InventoryInvariantViolationException")
        void commit_overOnHand_throws() {
            // reserved는 충분하나 on_hand가 부족한 비정상 상태(방어)
            Inventory inventory = newInventory(2, 5, -3);

            assertThatThrownBy(() -> inventory.commitReservation(3))
                    .isInstanceOf(InventoryInvariantViolationException.class)
                    .hasMessageContaining("실물 부족");
        }
    }

    @Nested
    @DisplayName("restoreStock")
    class RestoreStock {

        @Test
        @DisplayName("정상: onHand 증가·available 재계산")
        void restore_success() {
            Inventory inventory = newInventory(7, 2, 5);

            inventory.restoreStock(3);

            assertThat(inventory.getQuantityOnHand()).isEqualTo(10);
            assertThat(inventory.getQuantityReserved()).isEqualTo(2);
            assertThat(inventory.getQuantityAvailable()).isEqualTo(8);
        }

        @Test
        @DisplayName("실패: qty=0 → IllegalArgumentException")
        void restore_zero_throws() {
            Inventory inventory = newInventory(7, 2, 5);

            assertThatThrownBy(() -> inventory.restoreStock(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
