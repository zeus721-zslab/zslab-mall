package com.zslab.mall.inventory.repository;

import com.zslab.mall.inventory.entity.InventoryHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryHistoryRepository extends JpaRepository<InventoryHistory, Long> {
}
