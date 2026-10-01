-- V45: 클레임 처리 제안 기록 — claim_suggestion_record (D-250 · 운영 인박스 P2)
-- 작성일: 2026-10-01
-- 참조: docs/track-ops-inbox/recon-report-p2.md · decisions.md D-220(보완: 제안 입력 스냅샷 저장) · D-250
-- 목적: 관리자가 클레임을 승인·거부한 시점에 그때의 제안(규칙 키·입력 스냅샷)과 운영자 결정을 함께 남긴다 — 채택/거부 역추적.
--
-- 설계:
--   관리자 승인·거부 1회당 행 1개(같은 트랜잭션 · 전이가 롤백되면 행도 없다). 조회 시점에는 쓰지 않는다. append-only.
--   suggestion·rule_key·decision = ENUM — 4층위 enum 잠금 (1)DB · Java ClaimSuggestion·ClaimSuggestionRule·ClaimDecision.
--   input_snapshot = 규칙 입력(JSON·JSON_VALID CHECK).
--   claim_id·decided_by = 논리 참조(FK 없음) — 기록이 클레임·사용자 행 정리를 막지 않게 한다(V35 관례).
-- 데이터 안전: 신규 테이블 1개 — 기존 행 변경 없음.

CREATE TABLE claim_suggestion_record (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  claim_id        BIGINT        NOT NULL COMMENT '클레임 id 논리참조(FK 없음)',
  suggestion      ENUM('APPROVE','REVIEW') NOT NULL COMMENT '결정 시점 제안',
  rule_key        ENUM('NO_MATCH','EXCHANGE_STOCK_SHORT','UNSHIPPED_CANCEL','DEFECT_WITH_EVIDENCE','DEFECT_WITHOUT_EVIDENCE',
                       'CHANGE_OF_MIND') NOT NULL COMMENT '일치한 규칙(R0~R5)',
  input_snapshot  LONGTEXT      NOT NULL COMMENT '규칙 입력(JSON)',
  decision        ENUM('APPROVE','REJECT') NOT NULL COMMENT '운영자 결정',
  decided_by      BIGINT        NOT NULL COMMENT '결정한 관리자 User.id 논리참조',
  decided_at      DATETIME(6)   NOT NULL COMMENT '결정 시각(클레임 processed_at과 같은 값)',
  PRIMARY KEY (id),
  KEY ix_claim_suggestion_record_claim (claim_id),
  CONSTRAINT chk_claim_suggestion_record_snapshot CHECK (JSON_VALID(input_snapshot))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='클레임 처리 제안 기록(D-250)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 기록은 다른 테이블이 참조하지 않아 삭제해도 클레임·환불 흐름에 영향이 없다(제안 이력만 사라진다).
--
-- DROP TABLE claim_suggestion_record;
-- ============================================================
