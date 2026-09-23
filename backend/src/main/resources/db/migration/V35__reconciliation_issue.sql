-- V35: reconciliation_issue — 주문·결제 불일치 기록 (Track 104-2·D-216·invariants P1·P6)
-- 작성일: 2026-09-23
-- 참조: docs/track-104/recon-report-104-2.md · decisions.md D-216
-- 목적: PG에서 일어난 사실(결제 성공·환불 완료·취소 통지)이 내부 규칙과 충돌하면 거부·롤백하지 않고 불일치로 남기고(P1),
--   로그로만 끝나던 불일치를 영속 상태로 저장해 관리자 화면에 드러낸다(P6).
--
-- 설계:
--   issue_type = 11값 ENUM. 4층위 enum 잠금 (1)DB — Java ReconciliationIssueType·DTO(enum 직접 바인딩)·FE 상수와 1:1.
--   status = ENUM('OPEN','RESOLVED'). 해결 시 resolved_at·resolution_memo를 함께 채운다(CHECK로 쌍 강제). resolved_by는 관리자 해결만
--     채우고 NULL이면 시스템 자동 해소다(매칭 없던 PG 통지가 재전송으로 매칭된 경우).
--   dedupe_key = 유형별 대상 식별(예: payment:12·refund:7·claim-completed:3). (issue_type, dedupe_key) UNIQUE로 같은 불일치의
--     재통지·재점검을 1행으로 모은다(INSERT … ON DUPLICATE KEY UPDATE 멱등). 해결된 행도 키를 유지하므로 같은 불일치는 다시 열리지 않는다.
--   order_id·payment_id·refund_id·claim_id·delivery_id = 논리 참조(FK 없음) — 미결제 종료 주문 hard delete(ExpiredOrderCleanup)가
--     불일치 기록 때문에 막히지 않게 하고, 매칭 행 없는 PG 통지(주문·결제 id 없음)도 담는다.
--   detail = 유형별 세부(JSON·JSON_VALID CHECK) — 세부 사유·콜백 원문 값(attemptKey·provider·occurredAt 등)·조회 시점 상태.
-- 데이터 안전: 신규 테이블 — 기존 행 변경 없음.

CREATE TABLE reconciliation_issue (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  issue_type      ENUM('PG_PAYMENT_SUCCESS_CONFLICT','PG_PAYMENT_CANCEL_ON_PAID','PG_TID_CONFLICT','PG_UNMATCHED_CALLBACK',
                       'PG_REFUND_EXCEEDS_PAYMENT','PG_REFUND_SUCCESS_ON_FAILED','PAYMENT_CANCELLED_WITHOUT_REFUND',
                       'FULL_REFUND_PAYMENT_NOT_CANCELLED','FULL_REFUND_WITH_CONFIRMED_ITEM','REFUND_ON_INVALID_CLAIM',
                       'ITEM_STATE_DRIFT') NOT NULL COMMENT '불일치 유형(D-216)',
  dedupe_key      VARCHAR(191)  NOT NULL COMMENT '유형 내 중복 방지 키(대상 식별)',
  order_id        BIGINT        NULL     COMMENT '주문 id 논리참조(FK 없음·매칭 없는 PG 통지는 NULL)',
  payment_id      BIGINT        NULL     COMMENT '결제 id 논리참조',
  refund_id       BIGINT        NULL     COMMENT '환불 id 논리참조',
  claim_id        BIGINT        NULL     COMMENT '클레임 id 논리참조',
  delivery_id     BIGINT        NULL     COMMENT '배송 id 논리참조',
  pg_tid          VARCHAR(100)  NULL     COMMENT 'PG 결제 거래 id(통지 원문)',
  pg_refund_id    VARCHAR(100)  NULL     COMMENT 'PG 환불 id(통지 원문)',
  detail          LONGTEXT      NULL     COMMENT '유형별 세부(JSON)',
  status          ENUM('OPEN','RESOLVED') NOT NULL DEFAULT 'OPEN' COMMENT '처리 상태',
  detected_at     DATETIME(6)   NOT NULL COMMENT '최초 탐지 시각',
  resolved_at     DATETIME(6)   NULL     COMMENT '해결 시각',
  resolved_by     BIGINT        NULL     COMMENT '해결 관리자 User.id 논리참조(해결됐는데 NULL = 시스템 자동 해소)',
  resolution_memo VARCHAR(500)  NULL     COMMENT '해결 메모(필수 입력)',
  created_at      DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reconciliation_issue_type_dedupe (issue_type, dedupe_key),
  KEY ix_reconciliation_issue_status_detected (status, detected_at),
  KEY ix_reconciliation_issue_order_status (order_id, status),
  CONSTRAINT chk_reconciliation_issue_detail CHECK (detail IS NULL OR JSON_VALID(detail)),
  CONSTRAINT chk_reconciliation_issue_resolution CHECK (
    (status = 'OPEN' AND resolved_at IS NULL AND resolved_by IS NULL AND resolution_memo IS NULL)
    OR (status = 'RESOLVED' AND resolved_at IS NOT NULL AND resolution_memo IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문·결제 불일치 기록(P1·P6·D-216)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- DROP TABLE reconciliation_issue;
-- ============================================================
