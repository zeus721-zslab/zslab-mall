-- V36: 환불 PG 성공 사실 컬럼 · 불일치 유형 추가 (Track 104-3a·invariants P1·P6)
-- 작성일: 2026-09-23
-- 참조: docs/track-104/recon-report-104-3.md §2 · decisions.md D-216 §8 "104-3 필수" 2건
-- 목적:
--   refund.pg_refund_succeeded_at — 실패(FAILED) 처리한 환불에 PG가 성공을 통지한 사실을 환불 행에 남긴다. 상태는 FAILED 그대로(RFN-2)이고,
--     품목 기환불액·재개시 판정이 이 사실을 보고 같은 돈을 다시 환불하지 않게 한다(이중 환불 차단).
--   reconciliation_issue.issue_type — 완료(COMPLETED)된 환불에 PG 실패 통지(PG_REFUND_FAIL_ON_COMPLETED)를 추가한다(구 500·롤백·무한 재전송).
--
-- 설계:
--   issue_type = 12값 ENUM(V35 11값 + PG_REFUND_FAIL_ON_COMPLETED 끝에 추가). 4층위 enum 잠금 (1)DB — Java ReconciliationIssueType·FE 상수와 1:1.
--     V35 파일은 수정하지 않는다(적용된 마이그레이션의 체크섬이 바뀌면 Flyway validate가 기동을 막는다).
--   pg_refund_succeeded_at = NULL 허용·백필 없음(기존 PG_REFUND_SUCCESS_ON_FAILED 기록 0건 — 로컬 실측).
-- 데이터 안전: 컬럼 추가(NULL)·ENUM 끝에 값 추가 — 기존 행 변경 없음.

ALTER TABLE refund
  ADD COLUMN pg_refund_succeeded_at DATETIME(6) NULL COMMENT 'PG가 환불 성공을 통지한 시각(상태와 무관한 외부 사실)';

ALTER TABLE reconciliation_issue
  MODIFY COLUMN issue_type ENUM('PG_PAYMENT_SUCCESS_CONFLICT','PG_PAYMENT_CANCEL_ON_PAID','PG_TID_CONFLICT','PG_UNMATCHED_CALLBACK',
                                'PG_REFUND_EXCEEDS_PAYMENT','PG_REFUND_SUCCESS_ON_FAILED','PAYMENT_CANCELLED_WITHOUT_REFUND',
                                'FULL_REFUND_PAYMENT_NOT_CANCELLED','FULL_REFUND_WITH_CONFIRMED_ITEM','REFUND_ON_INVALID_CLAIM',
                                'ITEM_STATE_DRIFT','PG_REFUND_FAIL_ON_COMPLETED') NOT NULL COMMENT '불일치 유형(D-216)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- ENUM 축소는 PG_REFUND_FAIL_ON_COMPLETED 행이 있으면 실패한다 — 해당 행을 먼저 확인·처리한 뒤 실행한다.
--
-- ALTER TABLE reconciliation_issue
--   MODIFY COLUMN issue_type ENUM('PG_PAYMENT_SUCCESS_CONFLICT','PG_PAYMENT_CANCEL_ON_PAID','PG_TID_CONFLICT','PG_UNMATCHED_CALLBACK',
--                                 'PG_REFUND_EXCEEDS_PAYMENT','PG_REFUND_SUCCESS_ON_FAILED','PAYMENT_CANCELLED_WITHOUT_REFUND',
--                                 'FULL_REFUND_PAYMENT_NOT_CANCELLED','FULL_REFUND_WITH_CONFIRMED_ITEM','REFUND_ON_INVALID_CLAIM',
--                                 'ITEM_STATE_DRIFT') NOT NULL COMMENT '불일치 유형(D-216)';
-- ALTER TABLE refund DROP COLUMN pg_refund_succeeded_at;
-- ============================================================
