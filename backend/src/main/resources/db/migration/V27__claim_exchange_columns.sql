-- V27: 교환 클레임 옵션·재고 예약 표식 컬럼 (Track 83·D-177 결정 2·8)
-- 작성일: 2026-09-17
-- 참조: decisions.md D-177(교환 = 반품 흐름 + 관리자 교환품 발송·같은 상품·같은 가격·다른 옵션만·승인 시 교환 옵션 재고 예약)
--
-- exchange_variant_id  : 구매자가 요청한 교환 옵션(product_variant). 승인 시 판매 가능·같은 상품·같은 가격 재검증 후 예약 대상.
-- original_variant_id  : 승인 시점의 order_item.variant_id 스냅샷. 교환 완료 시 order_item.variant_id가 교환 옵션으로 갱신되므로
--                        회수품 재입고(검수 restock)와 이력 조회는 이 컬럼을 쓴다.
-- exchange_reserved_at : 교환 옵션 재고 예약 시각. NULL=예약 없음. 승인(reserve)·완료(commit)/거부·검수 FAIL(release) 전이의
--                        멱등 표식(예약은 inventory_history를 남기지 않아 SoT가 필요).
-- original_option_label: 승인 시점 order_item.option_label 스냅샷(외부 검토 반영). 교환 완료 후 옵션값이 삭제·변경돼도 원 옵션 표기를
--                        보존한다(타입·길이 order_item.option_label VARCHAR(500)과 동일). NULL이면 응답이 original_variant_id로 재조립.
-- FK는 order_item.variant_id·inventory.variant_id와 같은 관례(product_variant RESTRICT)를 따른다. 기존 컬럼·ENUM 무변경.
ALTER TABLE claim
  ADD COLUMN exchange_variant_id BIGINT NULL COMMENT '교환 요청 옵션 variant id(EXCHANGE 한정·D-177)' AFTER restock,
  ADD COLUMN original_variant_id BIGINT NULL COMMENT '승인 시점 원 옵션 variant id 스냅샷(EXCHANGE 한정·D-177)' AFTER exchange_variant_id,
  ADD COLUMN exchange_reserved_at DATETIME(6) NULL COMMENT '교환 옵션 재고 예약 시각·NULL=예약 없음(D-177)' AFTER original_variant_id,
  ADD COLUMN original_option_label VARCHAR(500) NULL COMMENT '승인 시점 원 옵션 라벨 스냅샷(EXCHANGE 한정·D-177)' AFTER exchange_reserved_at,
  ADD CONSTRAINT fk_claim_exchange_variant FOREIGN KEY (exchange_variant_id) REFERENCES product_variant (id)
      ON DELETE RESTRICT ON UPDATE CASCADE,
  ADD CONSTRAINT fk_claim_original_variant FOREIGN KEY (original_variant_id) REFERENCES product_variant (id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- ALTER TABLE claim DROP FOREIGN KEY fk_claim_original_variant;
-- ALTER TABLE claim DROP FOREIGN KEY fk_claim_exchange_variant;
-- ALTER TABLE claim DROP COLUMN original_option_label;
-- ALTER TABLE claim DROP COLUMN exchange_reserved_at;
-- ALTER TABLE claim DROP COLUMN original_variant_id;
-- ALTER TABLE claim DROP COLUMN exchange_variant_id;
-- ============================================================
