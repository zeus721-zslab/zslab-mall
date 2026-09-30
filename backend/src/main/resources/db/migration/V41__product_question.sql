-- V41: 상품 Q&A — product_question (Track 106-2)
-- 작성일: 2026-09-30
-- 참조: docs/track-106-2/recon-report.md B1(리뷰 숨김·목록 패턴)·B6(명명·롤백 관례)
-- 목적: 구매자의 공개 상품 질문과 해당 상품 셀러의 답변 1개를 한 행에 저장한다. 관리자는 숨김·해제만 한다.
--
-- 설계:
--   product_question = 질문 1건 = 행 1개. 답변은 질문당 최대 1개이고 등록 후 수정만 있어(삭제 없음) 별도 테이블 없이 같은 행의
--                      answer_content·answered_at·answered_by 3컬럼으로 둔다. 셋은 함께 채워지거나 함께 비어 있어야 한다(CHECK).
--                      status = VARCHAR + CHECK(VISIBLE·HIDDEN) — 4층위 enum 잠금 (1)DB · Java ProductQuestionStatus · 관리자 DTO @Pattern.
--                      hidden_reason = 관리자 숨김 사유. HIDDEN일 때만 값이 있고 해제 시 비운다(리뷰 V40 chk_review_hidden_reason과 같은 쌍 CHECK).
--                      buyer_id = 질문 구매자 User.id · answered_by = 답변한 셀러 소속 User.id(둘 다 논리참조·FK 미적용·행위자 컬럼 관례).
--                      질문자 삭제는 soft delete(deleted_at). created_by는 AuditorAware 미구현으로 항상 NULL.
--   FK = ON DELETE RESTRICT ON UPDATE RESTRICT(최근 관례·V40). 대상 product는 운영 경로에서 하드 삭제되지 않는다.
-- 데이터 안전: 신규 테이블 1개 — 기존 행 변경 없음.

CREATE TABLE product_question (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  public_id       CHAR(30)      NOT NULL COMMENT 'ULID+prefix pqn_',
  product_id      BIGINT        NOT NULL COMMENT 'FK→product(N:1·상품별 목록 기준)',
  buyer_id        BIGINT        NOT NULL COMMENT '질문 구매자 User.id 논리참조(FK 미적용)',
  content         VARCHAR(500)  NOT NULL COMMENT '질문 본문(trim 후 5~500자)',
  status          VARCHAR(20)   NOT NULL COMMENT '공개 상태(VISIBLE·HIDDEN)',
  hidden_reason   VARCHAR(200)  NULL     COMMENT '숨김 사유(관리자 입력·HIDDEN일 때만)',
  answer_content  VARCHAR(1000) NULL     COMMENT '셀러 답변 본문(답변 전 NULL)',
  answered_at     DATETIME(6)   NULL     COMMENT '답변 등록·수정 시각(답변 전 NULL)',
  answered_by     BIGINT        NULL     COMMENT '답변한 셀러 소속 User.id 논리참조(답변 전 NULL)',
  created_at      DATETIME(6)   NOT NULL,
  created_by      BIGINT        NULL,
  updated_at      DATETIME(6)   NOT NULL,
  updated_by      BIGINT        NULL,
  deleted_at      DATETIME(6)   NULL,
  deleted_by      BIGINT        NULL,
  delete_reason   VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_question_public_id (public_id),
  -- 공개 목록·즉시 답 후보는 product_id = ? AND status = 'VISIBLE' AND deleted_at IS NULL로 좁힌 뒤 최신순 정렬한다(리뷰 ix_review_product_list와 같은 구성).
  KEY ix_product_question_product_list (product_id, status, deleted_at, created_at),
  -- 내 질문은 buyer_id = ? AND deleted_at IS NULL 최신순.
  KEY ix_product_question_buyer_list (buyer_id, deleted_at, created_at),
  CONSTRAINT chk_product_question_status CHECK (status IN ('VISIBLE','HIDDEN')),
  CONSTRAINT chk_product_question_hidden_reason CHECK (
    (status = 'HIDDEN' AND hidden_reason IS NOT NULL AND CHAR_LENGTH(TRIM(hidden_reason)) > 0)
    OR (status = 'VISIBLE' AND hidden_reason IS NULL)),
  CONSTRAINT chk_product_question_answer CHECK (
    (answer_content IS NULL AND answered_at IS NULL AND answered_by IS NULL)
    OR (answer_content IS NOT NULL AND answered_at IS NOT NULL AND answered_by IS NOT NULL)),
  CONSTRAINT fk_product_question_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 Q&A(질문+답변 1개·SOFT·public_id pqn_·Track 106-2)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 관리자 숨김 감사 행(audit_log.target_type = 'PRODUCT_QUESTION')은 append-only라 남긴다.
--
-- DROP TABLE product_question;
-- ============================================================
