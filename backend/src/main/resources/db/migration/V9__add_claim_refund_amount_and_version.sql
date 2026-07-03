-- V9: claim 테이블 EXCHANGE 차액환불 확장 컬럼 추가 (Track 30 Phase 2·D-115)
-- 작성일: 2026-07-03
-- 참조: decisions.md D-115 결정2(refund_amount 승인 시 확정·nullable)·결정4(@Version 낙관적 락)
-- 운영 데이터 0건 가정: V6__ 주석 실측(claim 시드 0건) 상속 → 백필 SQL 불요·version NOT NULL DEFAULT 0 즉시 적용

-- D-115 결정2: 교환 클레임 환불 금액. 승인(approve) 시 확정하며 차액 없는 교환은 NULL(=refundAmount==0 경로).
--   refund.amount BIGINT NOT NULL(V1__init.sql L693)과 타입 정합. 위치는 processed_at 뒤(V6 picked_up_at 앞 삽입).
-- D-115 결정4: JPA @Version 낙관적 락. 종결 전이 동시성 방어. 기존 행 0건이나 NOT NULL DEFAULT 0으로 재적용 안전성 확보.
ALTER TABLE claim
  ADD COLUMN refund_amount BIGINT NULL COMMENT '교환 클레임 환불 금액·승인 시 확정·NULL=차액없음(D-115 결정2)' AFTER processed_at,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA 낙관적 락·@Version(D-115 결정4)' AFTER previous_order_item_status;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- ALTER TABLE claim DROP COLUMN version;
-- ALTER TABLE claim DROP COLUMN refund_amount;
-- ============================================================
