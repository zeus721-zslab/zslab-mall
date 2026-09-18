-- V29: 정산 품목 스냅샷 테이블·지급예정일·계좌/수수료율 nullable (Track 85 관리자 정산)
-- 작성일: 2026-09-18
-- 참조: docs/track-85/recon-report.md · decisions.md D-133(월 배치)·D-134(전이)·D-168(refund 가드)
--
-- settlement_item : 정산 헤더가 집계한 품목(SALE=구매확정 order_item·REFUND=완료 환불)의 스냅샷. 정산 시점의 상품명·옵션·금액·
--                   수수료율(order_item.commission_rate·V30)·수수료를 박제해 사후 상품·율 변경과 무관하게 재현·검수한다.
--                   order_item_id·refund_id는 논리참조(FK 미적용·aggregate-boundary D-01). 재생성(PENDING 한정)은 품목→헤더 순으로
--                   삭제 후 재집계하므로 settlement FK는 RESTRICT다.
-- dedup_key       : 같은 정산에 같은 품목(SALE: order_item / REFUND: refund)이 두 번 적재되는 것을 DB에서 차단한다.
--                   UNIQUE(settlement_id, item_type, order_item_id, refund_id)는 SALE 행의 refund_id가 NULL이라 MariaDB UNIQUE
--                   NULL 시맨틱(NULL≠NULL)으로 무효 → V13(category.dedup_key) 선례대로 STORED generated 컬럼에 COALESCE(refund_id, 0)를
--                   넣어 유니크를 강제한다. generated 식이 참조하는 settlement_id에는 ON UPDATE CASCADE FK를 걸 수 없어(V13 실측)
--                   fk_settlement_item_settlement는 ON UPDATE RESTRICT다(settlement.id는 AUTO_INCREMENT·갱신 경로 없음).
-- settlement 변경 : scheduled_pay_date(지급예정일 = 기간 말일 + settlement.payout-offset-days·생성 시 계산) 추가.
--                   bank_account_id NULL 허용 — 계좌는 생성 조건이 아니라 지급(PAID) 조건이며 지급 시점 주 계좌를 스냅샷한다.
--                   commission_rate NULL 허용 — 율은 품목 스냅샷(settlement_item.commission_rate)이 SoT가 되므로 헤더 율은
--                   신규 생성분부터 NULL(DROP은 이월).
-- 데이터 안전 : settlement 기존 행은 로컬·운영 모두 0건(정찰 실측) → 백필 없음. NULL 허용 완화는 기존 값을 바꾸지 않는다.

-- (1) 정산 품목 스냅샷
CREATE TABLE settlement_item (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  settlement_id    BIGINT       NOT NULL COMMENT 'FK→settlement(N:1)',
  item_type        ENUM('SALE','REFUND') NOT NULL COMMENT 'SALE=구매확정 매출·REFUND=완료 환불 차감',
  order_item_id    BIGINT       NOT NULL COMMENT 'OrderItem.id 논리참조(FK 미적용)',
  refund_id        BIGINT       NULL     COMMENT 'Refund.id 논리참조·REFUND만(SALE은 NULL)',
  order_public_id  CHAR(30)     NOT NULL COMMENT '주문 public_id 스냅샷(ord_)',
  product_name     VARCHAR(200) NOT NULL COMMENT '상품명 스냅샷(order_item.product_name)',
  option_label     VARCHAR(500) NULL     COMMENT '옵션 라벨 스냅샷(order_item.option_label)',
  quantity         INT          NOT NULL COMMENT '수량 스냅샷',
  amount           BIGINT       NOT NULL COMMENT 'SALE=order_item.total_price·REFUND=refund.amount',
  commission_rate  INT          NOT NULL COMMENT '적용 수수료율 스냅샷·basis-point(order_item.commission_rate)',
  fee_amount       BIGINT       NOT NULL COMMENT 'SALE=floor(amount×rate/10000)·REFUND=0(환불 시 수수료 환급 없음)',
  occurred_at      DATETIME(6)  NOT NULL COMMENT 'SALE=order_item.confirmed_at·REFUND=refund.refunded_at',
  created_at       DATETIME(6)  NOT NULL,
  created_by       BIGINT       NULL     COMMENT 'AbstractCreatedOnlyEntity 관례(AuditorAware 미구현·NULL)',
  dedup_key        VARCHAR(80)  AS (CONCAT(settlement_id, ':', item_type, ':', order_item_id, ':', COALESCE(refund_id, 0))) STORED
                                COMMENT '중복 적재 방지 파생키(SALE은 refund_id NULL→0 치환)',
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_item_dedup (dedup_key),
  KEY ix_settlement_item_lookup (settlement_id, item_type, occurred_at),
  CONSTRAINT fk_settlement_item_settlement FOREIGN KEY (settlement_id) REFERENCES settlement (id)
      ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='정산 품목 스냅샷(STL 종속·ARCHIVE·Track 85)';

-- (2) 정산 헤더: 지급예정일·계좌/율 nullable
ALTER TABLE settlement
  ADD COLUMN scheduled_pay_date DATE NULL COMMENT '지급예정일 = 기간 말일 + N일(settlement.payout-offset-days·생성 시 계산)' AFTER paid_at,
  MODIFY COLUMN bank_account_id BIGINT NULL COMMENT 'FK→seller_bank_account·지급(PAID) 시점 주 계좌 스냅샷(STL-3)·생성 시 NULL',
  MODIFY COLUMN commission_rate INT NULL COMMENT '(구) 헤더 수수료율 스냅샷·Track 85부터 품목 스냅샷이 SoT라 신규 행 NULL·DROP 이월';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행한다(NULL 값이 있으면 NOT NULL 복원 전 보정 필요).
--
-- ALTER TABLE settlement
--   MODIFY COLUMN commission_rate INT NOT NULL DEFAULT 1000,
--   MODIFY COLUMN bank_account_id BIGINT NOT NULL,
--   DROP COLUMN scheduled_pay_date;
-- DROP TABLE settlement_item;
-- ============================================================
