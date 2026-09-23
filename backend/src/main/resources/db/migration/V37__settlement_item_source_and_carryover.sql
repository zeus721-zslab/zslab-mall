-- V37: 정산 품목 전역 출처 유니크·이월(CARRYOVER) 유형 (Track 104-3b·결정 ⑦⑧)
-- 작성일: 2026-09-24
-- 참조: docs/track-104/recon-report-104-3b.md §2·§4.2
-- 목적:
--   source_id — 정산 품목의 출처 id. (item_type, source_id) 전역 UNIQUE로 같은 사실(매출 품목·완료 환불·음수 정산)이 두 정산에
--     편입되는 것을 DB가 막는다. 정산 조회는 "기간 말까지 + 아직 편입되지 않은 사실"을 모두 가져오므로(⑦) 이 키가 편입 여부의 판정 기준이다.
--     SALE = order_item.id · REFUND = refund.id · CARRYOVER = 원(음수) 정산 settlement.id.
--     기존 dedup_key(정산 단위 UNIQUE)는 그대로 둔다(β).
--   item_type CARRYOVER — 순지급액 음수로 지급이 막힌 정산의 부족분을 다음 정산에서 차감하는 행(⑧).
--   이월 행은 주문 품목이 없어 채울 수 없는 컬럼만 NULL 허용으로 완화한다:
--     order_item_id·order_public_id·product_name·quantity (amount·commission_rate(0)·fee_amount(0)·occurred_at은 이월 행도 채운다).
--     완화는 CARRYOVER 행에만 적용된다 — SALE·REFUND 행의 4개 컬럼과 모든 행의 source_id는 CHECK(⑤)로 필수다.
--   settlement.carryover_amount — 이월 차감 합(CARRYOVER 품목 금액 합). 순지급액 = gross − fee − refund − carryover(STL-1).
--     환불(refund_amount)과 섞지 않고 별도 컬럼으로 둔다. 기존 행은 이월이 없으므로 DEFAULT 0이 곧 정확한 값이다.
--
-- 설계:
--   item_type·order_item_id는 dedup_key(STORED generated) 식이 참조하는 컬럼이다. 이월 행은 order_item_id가 NULL이라 dedup_key도 NULL이 되어
--     정산 단위 UNIQUE 비교에서 빠진다(NULL≠NULL) — 이월 행의 중복은 source UNIQUE가 막는다.
--   source_id는 NULL 허용으로 추가 → 기존 행 백필 → UNIQUE 생성 순서다.
--
-- 데이터 안전·재실행: 컬럼 추가(NULL)·ENUM 끝에 값 추가·NOT NULL 완화 — 기존 값 변경은 source_id 백필뿐.
--   UNIQUE 추가는 같은 매출 품목·환불이 두 정산에 들어간 기존 데이터가 있으면 실패한다(정찰 실측(로컬): SALE 132행·order_item 중복 0·REFUND 0).
--   DDL은 문장별 auto-commit이라 ③ 실패 시 ①②는 남는다 → 모든 문장을 멱등하게 작성해 중복 정리 → flyway repair → 재기동으로 재실행한다(V32 선례).
--   사전 점검 SELECT:
--     SELECT item_type, order_item_id, refund_id, COUNT(*) FROM settlement_item GROUP BY item_type, order_item_id, refund_id HAVING COUNT(*) > 1;

-- ① 이월 유형·출처 컬럼·이월 행이 채울 수 없는 컬럼 완화
ALTER TABLE settlement_item
  MODIFY COLUMN item_type ENUM('SALE','REFUND','CARRYOVER') NOT NULL COMMENT 'SALE=구매확정 매출·REFUND=완료 환불 차감·CARRYOVER=음수 정산 이월 차감',
  MODIFY COLUMN order_item_id BIGINT NULL COMMENT 'OrderItem.id 논리참조(FK 미적용)·CARRYOVER는 NULL',
  MODIFY COLUMN order_public_id CHAR(30) NULL COMMENT '주문 public_id 스냅샷(ord_)·CARRYOVER는 NULL',
  MODIFY COLUMN product_name VARCHAR(200) NULL COMMENT '상품명 스냅샷(order_item.product_name)·CARRYOVER는 NULL',
  MODIFY COLUMN quantity INT NULL COMMENT '수량 스냅샷·CARRYOVER는 NULL',
  ADD COLUMN IF NOT EXISTS source_id BIGINT NULL
    COMMENT '출처 id(SALE=order_item.id·REFUND=refund.id·CARRYOVER=원 정산 settlement.id)·(item_type, source_id) 전역 UNIQUE'
    AFTER refund_id;

-- ② 기존 행 백필(SALE·REFUND만 존재)
UPDATE settlement_item SET source_id = order_item_id WHERE item_type = 'SALE' AND source_id IS NULL;
UPDATE settlement_item SET source_id = refund_id WHERE item_type = 'REFUND' AND source_id IS NULL;

-- ③ 같은 사실은 한 정산에만
ALTER TABLE settlement_item
  ADD UNIQUE KEY IF NOT EXISTS uk_settlement_item_source (item_type, source_id);

-- ④ 정산 헤더 이월 차감 합
ALTER TABLE settlement
  ADD COLUMN IF NOT EXISTS carryover_amount BIGINT NOT NULL DEFAULT 0 COMMENT '이월 차감 합(CARRYOVER 품목 금액 합)' AFTER refund_amount,
  MODIFY COLUMN net_amount BIGINT NOT NULL COMMENT '정산액=gross-fee-refund-carryover(STL-1)';

-- ⑤ 유형별 필수 컬럼(외부 검토 라운드 1): ①의 NULL 완화는 이월(CARRYOVER) 행만을 위한 것이다. SALE·REFUND 행은 주문 품목 스냅샷 4개가
--    그대로 필수이고, 모든 유형은 source_id가 필수다 — source_id가 NULL이면 UNIQUE 비교에서 빠져(NULL≠NULL) 전역 유니크가 그 행을 보호하지 못한다.
--    ②의 백필 뒤라 기존 행은 통과한다(정찰 실측(로컬): SALE 132행 source_id NULL 0·4컬럼 NULL 0). 마지막 문장이라 IF NOT EXISTS 없이 둔다
--    (앞 문장 실패 시 여기까지 오지 않고, 이 문장이 성공하면 마이그레이션이 끝난다).
ALTER TABLE settlement_item
  ADD CONSTRAINT chk_settlement_item_source_shape CHECK (
    source_id IS NOT NULL
    AND (item_type = 'CARRYOVER'
      OR (order_item_id IS NOT NULL AND order_public_id IS NOT NULL AND product_name IS NOT NULL AND quantity IS NOT NULL)));

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- CARRYOVER 행이 있으면 ENUM 축소·NOT NULL 복원이 실패한다 — 이월 행과 그 정산을 먼저 확인·처리한 뒤 실행한다.
--
-- ALTER TABLE settlement_item DROP CONSTRAINT chk_settlement_item_source_shape;
-- ALTER TABLE settlement
--   DROP COLUMN carryover_amount,
--   MODIFY COLUMN net_amount BIGINT NOT NULL COMMENT '정산액=gross-fee-refund(STL-1)';
-- ALTER TABLE settlement_item DROP INDEX uk_settlement_item_source;
-- ALTER TABLE settlement_item
--   DROP COLUMN source_id,
--   MODIFY COLUMN quantity INT NOT NULL COMMENT '수량 스냅샷',
--   MODIFY COLUMN product_name VARCHAR(200) NOT NULL COMMENT '상품명 스냅샷(order_item.product_name)',
--   MODIFY COLUMN order_public_id CHAR(30) NOT NULL COMMENT '주문 public_id 스냅샷(ord_)',
--   MODIFY COLUMN order_item_id BIGINT NOT NULL COMMENT 'OrderItem.id 논리참조(FK 미적용)',
--   MODIFY COLUMN item_type ENUM('SALE','REFUND') NOT NULL COMMENT 'SALE=구매확정 매출·REFUND=완료 환불 차감';
-- ============================================================
