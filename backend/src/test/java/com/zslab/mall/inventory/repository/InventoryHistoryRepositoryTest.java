package com.zslab.mall.inventory.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.Batch1DataJpaTestBase;
import com.zslab.mall.inventory.entity.Inventory;
import com.zslab.mall.inventory.entity.InventoryHistory;
import com.zslab.mall.inventory.enums.InventoryHistoryChangeType;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link InventoryHistoryRepository} @DataJpaTest — CRUD·FK·DB ENUM constraint 검증.
 */
class InventoryHistoryRepositoryTest extends Batch1DataJpaTestBase {

    @Autowired
    private InventoryHistoryRepository inventoryHistoryRepository;

    /**
     * FK 의존 체인(inventory→product_variant→...)이 깊어 FOREIGN_KEY_CHECKS=0로 재고 행 직접 시딩한다(LT-02 try-finally 필수).
     */
    private Inventory seedInventory() {
        try {
            entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS=0").executeUpdate();
            entityManager.getEntityManager().createNativeQuery(
                "INSERT INTO inventory (variant_id, quantity_on_hand, quantity_reserved, quantity_available, created_at, updated_at) "
                + "VALUES (1, 100, 0, 100, NOW(6), NOW(6))")
                .executeUpdate();
        } finally {
            entityManager.getEntityManager().createNativeQuery("SET FOREIGN_KEY_CHECKS=1").executeUpdate();
        }
        long id = ((Number) entityManager.getEntityManager()
            .createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        return entityManager.find(Inventory.class, id);
    }

    @Test
    @DisplayName("save+findById 성공: inventory·changeType·quantityDelta 보존·createdAt 자동 설정")
    void save_findById_success() {
        Inventory inventory = seedInventory();
        InventoryHistory saved = inventoryHistoryRepository.saveAndFlush(
            InventoryHistory.create(inventory, InventoryHistoryChangeType.ORDER, -1, "ORDER", null, null));
        entityManager.clear();

        Optional<InventoryHistory> found = inventoryHistoryRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getInventory().getId()).isEqualTo(inventory.getId());
        assertThat(found.get().getChangeType()).isEqualTo(InventoryHistoryChangeType.ORDER);
        assertThat(found.get().getQuantityDelta()).isEqualTo(-1);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("inventory_id FK 위반 삽입 → PersistenceException (FK RESTRICT)")
    void insert_invalidInventoryId_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO inventory_history (inventory_id, change_type, quantity_delta, reference_type, created_at) "
                    + "VALUES (99999, 'ORDER', -1, 'ORDER', NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }

    @Test
    @DisplayName("change_type ENUM 외 값 삽입 → PersistenceException (DB ENUM constraint)")
    void insert_invalidChangeType_throwsPersistenceException() {
        assertThatThrownBy(() ->
            entityManager.getEntityManager()
                .createNativeQuery(
                    "INSERT INTO inventory_history (inventory_id, change_type, quantity_delta, reference_type, created_at) "
                    + "VALUES (1, 'INVALID_TYPE', 1, 'ORDER', NOW(6))")
                .executeUpdate()
        ).isInstanceOf(PersistenceException.class);
    }
}
