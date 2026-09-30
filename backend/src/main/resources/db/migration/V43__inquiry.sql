-- V43: 운영자 문의(1:1 문의) — inquiry (Track 106-4)
-- 작성일: 2026-09-30
-- 참조: docs/track-106-4/recon-report.md(결정 필요 BE 1~9 확정안) · V41 product_question(1문1답·쌍 CHECK 선례)
-- 목적: 로그인 구매자가 운영자에게 남기는 비공개 텍스트 문의와 관리자 답변 1개를 한 행에 저장한다. 셀러에게 전달하지 않는다.
--
-- 설계:
--   inquiry = 문의 1건 = 행 1개. 답변은 문의당 최대 1개이고 등록 후 수정만 있어(삭제 없음) 같은 행의 answer_content·answered_at·
--             answered_by 3컬럼으로 둔다. 셋은 함께 채워지거나 함께 비어 있어야 한다(CHECK). 상태 컬럼은 두지 않는다 — 미답변·답변 완료는
--             answered_at에서 파생한다(전이가 하나뿐이라 컬럼을 두면 원천이 둘이 된다).
--             category = VARCHAR + CHECK(고정 5종) — 4층위 enum 잠금 (1)DB · Java InquiryCategory · 구매자 DTO @Pattern · FE 상수.
--             order_id = 첨부 주문(선택·주문 단위). 구매자 본인 주문만 첨부된다(서비스가 소유 대조).
--             answer_checked_at = 구매자가 현재 답변을 확인한 시각. 답변 전에는 NULL이어야 하고(CHECK), 관리자가 답변을 수정하면 NULL로
--             되돌려 다시 미확인이 된다. 미확인 = answered_at IS NOT NULL AND answer_checked_at IS NULL.
--             buyer_id = 문의 구매자 User.id · answered_by = 답변한 관리자 User.id(둘 다 논리참조·FK 미적용·행위자 컬럼 관례).
--             구매자 삭제는 soft delete(deleted_at). created_by는 AuditorAware 미구현으로 항상 NULL.
--   FK = ON DELETE RESTRICT ON UPDATE RESTRICT(최근 관례·V41). 대상 order는 운영 경로에서 하드 삭제되지 않는다.
-- 데이터 안전: 신규 테이블 1개 — 기존 행 변경 없음.

CREATE TABLE inquiry (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  public_id          CHAR(30)      NOT NULL COMMENT 'ULID+prefix inq_',
  buyer_id           BIGINT        NOT NULL COMMENT '문의 구매자 User.id 논리참조(FK 미적용)',
  order_id           BIGINT        NULL     COMMENT 'FK→order(첨부 주문·선택)',
  category           VARCHAR(30)   NOT NULL COMMENT '카테고리(ORDER_PAYMENT·DELIVERY·CLAIM·ACCOUNT·OTHER)',
  content            VARCHAR(500)  NOT NULL COMMENT '문의 본문(trim 후 5~500자)',
  answer_content     VARCHAR(1000) NULL     COMMENT '관리자 답변 본문(답변 전 NULL)',
  answered_at        DATETIME(6)   NULL     COMMENT '답변 등록·수정 시각(답변 전 NULL)',
  answered_by        BIGINT        NULL     COMMENT '답변한 관리자 User.id 논리참조(답변 전 NULL)',
  answer_checked_at  DATETIME(6)   NULL     COMMENT '구매자가 현재 답변을 확인한 시각(미확인·답변 전 NULL)',
  created_at         DATETIME(6)   NOT NULL,
  created_by         BIGINT        NULL,
  updated_at         DATETIME(6)   NOT NULL,
  updated_by         BIGINT        NULL,
  deleted_at         DATETIME(6)   NULL,
  deleted_by         BIGINT        NULL,
  delete_reason      VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inquiry_public_id (public_id),
  -- 내 문의는 buyer_id = ? AND deleted_at IS NULL 최신순.
  KEY ix_inquiry_buyer_list (buyer_id, deleted_at, created_at),
  -- 관리자 미답변 목록·대시보드 미답변 수는 answered_at IS NULL AND deleted_at IS NULL(목록은 오래된 순).
  KEY ix_inquiry_unanswered (answered_at, deleted_at, created_at),
  CONSTRAINT chk_inquiry_category CHECK (category IN ('ORDER_PAYMENT','DELIVERY','CLAIM','ACCOUNT','OTHER')),
  CONSTRAINT chk_inquiry_answer CHECK (
    (answer_content IS NULL AND answered_at IS NULL AND answered_by IS NULL)
    OR (answer_content IS NOT NULL AND answered_at IS NOT NULL AND answered_by IS NOT NULL)),
  CONSTRAINT chk_inquiry_answer_checked CHECK (answer_checked_at IS NULL OR answered_at IS NOT NULL),
  CONSTRAINT fk_inquiry_order FOREIGN KEY (order_id) REFERENCES `order` (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='운영자 문의(문의+답변 1개·SOFT·public_id inq_·Track 106-4)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 관리자 답변 감사 행(audit_log.target_type = 'INQUIRY')은 append-only라 남긴다.
--
-- DROP TABLE inquiry;
-- ============================================================
