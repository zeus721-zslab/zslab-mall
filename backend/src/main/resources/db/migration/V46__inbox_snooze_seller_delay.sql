-- V46: 운영 인박스 보류 유형에 셀러 지연(SELLER_DELAY) 추가 (D-252 · 운영 인박스 P3)
-- 작성일: 2026-10-02
-- 참조: docs/track-ops-inbox/recon-report-p3.md · decisions.md D-252
-- 목적: 관리자 인박스 신규 유형 SELLER_DELAY를 보류할 수 있게 item_type CHECK 허용값을 1개 늘린다(4층위 enum 잠금 (1)DB).
-- 데이터 안전: 허용값 추가만 — 기존 행은 모두 새 CHECK를 만족한다.

ALTER TABLE inbox_snooze DROP CONSTRAINT chk_inbox_snooze_item_type;

ALTER TABLE inbox_snooze ADD CONSTRAINT chk_inbox_snooze_item_type CHECK (item_type IN (
  'CLAIM_REQUESTED','CLAIM_FOLLOWUP','LONG_SHIPPING','INQUIRY_UNANSWERED','SELLER_REVIEW','PRODUCT_APPROVAL',
  'SETTLEMENT_CONFIRM','SETTLEMENT_PAYOUT','RECONCILIATION_OPEN','SELLER_DELAY','DELIVERY_READY','QUESTION_UNANSWERED','LOW_STOCK'));

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 셀러 지연 보류 행을 먼저 지워야 12값 CHECK를 다시 걸 수 있다(보류는 표시 오버레이라 삭제해도 원천 데이터에 영향 없음).
--
-- DELETE FROM inbox_snooze WHERE item_type = 'SELLER_DELAY';
-- ALTER TABLE inbox_snooze DROP CONSTRAINT chk_inbox_snooze_item_type;
-- ALTER TABLE inbox_snooze ADD CONSTRAINT chk_inbox_snooze_item_type CHECK (item_type IN (
--   'CLAIM_REQUESTED','CLAIM_FOLLOWUP','LONG_SHIPPING','INQUIRY_UNANSWERED','SELLER_REVIEW','PRODUCT_APPROVAL',
--   'SETTLEMENT_CONFIRM','SETTLEMENT_PAYOUT','RECONCILIATION_OPEN','DELIVERY_READY','QUESTION_UNANSWERED','LOW_STOCK'));
-- ============================================================
