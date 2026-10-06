-- V47: 등치 조건 2개 exists 쿼리에 맞는 복합 인덱스 2개 추가 (자체 평점 3-2(b) · S8 #49 · #50)

-- OrderItemRepository.existsByOrderIdAndItemStatus: WHERE order_id = ? AND item_status = ?
CREATE INDEX ix_order_item_order_status ON order_item (order_id, item_status);

-- InventoryHistoryRepository.existsByReferenceTypeAndReferenceId: WHERE reference_type = ? AND reference_id = ?
CREATE INDEX ix_inventory_history_reference ON inventory_history (reference_type, reference_id);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- DROP INDEX ix_inventory_history_reference ON inventory_history;
-- DROP INDEX ix_order_item_order_status ON order_item;
