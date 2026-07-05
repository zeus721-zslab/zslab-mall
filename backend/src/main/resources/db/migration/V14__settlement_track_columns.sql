-- V14: Settlement 트랙 P1 컬럼·제약 (Track 48 정산 DDL·Entity 층)
-- 작성일: 2026-07-05
-- 참조: docs/track-48/recon-report.md(2차 정찰)·decisions.md D-133
-- 목적: 정산 집계 P3에 필요한 (1) order_item 구매확정 시각 (2) seller/settlement 수수료율(basis-point) (3) 정산 기간 멱등 제약 신설.
--
-- 설계(확정): commission_rate = basis-point 정수(INT). 1000 = 10.00%. fee = gross × commission_rate / 10000.
--   confirmed_at = 구매확정(CONFIRMED 전이) 도메인 발생 시각. updated_at(최종 수정)과 의미가 다르며 정산 월 귀속 기준이다.
--   settlement.commission_rate = 정산 생성 시점 seller 율 스냅샷(사후 seller 율 변경과 무관하게 재현성 확보).
--
-- 데이터 안전: order_item 기존 CONFIRMED 행 backfill 없음(confirmed_at NULL 허용·미확정 항목과 동일 표현). 운영 데이터 0건 전제.
--   seller·settlement 기존 행은 NOT NULL DEFAULT 1000으로 자동 backfill(별도 UPDATE 불요).

-- (1) order_item 구매확정 시각(NULL=미확정)
ALTER TABLE order_item
  ADD COLUMN confirmed_at DATETIME(6) NULL COMMENT '구매확정(CONFIRMED 전이) 발생 시각·정산 월 귀속 기준·updated_at과 구분' AFTER item_status;

-- (2) seller 수수료율 스냅샷 소스(basis-point·1000=10.00%·기존 행 DEFAULT 1000 backfill)
ALTER TABLE seller
  ADD COLUMN commission_rate INT NOT NULL DEFAULT 1000 COMMENT '판매 수수료율·basis-point(1000=10.00%)·정산 fee 산정 소스' AFTER status;

-- (3) settlement 수수료율 스냅샷(정산 생성 시점 seller 율 박제·DEFAULT는 마이그레이션 편의·신규 행은 앱이 명시 세팅)
ALTER TABLE settlement
  ADD COLUMN commission_rate INT NOT NULL DEFAULT 1000 COMMENT '정산 생성 시점 수수료율 스냅샷·basis-point(1000=10.00%)' AFTER fee_amount;

-- (4) 정산 기간 멱등 제약(같은 seller·기간 중복 정산 차단)
ALTER TABLE settlement
  ADD CONSTRAINT uk_settlement_seller_period UNIQUE (seller_id, period_start, period_end);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행한다.
--
-- ALTER TABLE settlement DROP INDEX uk_settlement_seller_period;
-- ALTER TABLE settlement DROP COLUMN commission_rate;
-- ALTER TABLE seller DROP COLUMN commission_rate;
-- ALTER TABLE order_item DROP COLUMN confirmed_at;
-- ============================================================
