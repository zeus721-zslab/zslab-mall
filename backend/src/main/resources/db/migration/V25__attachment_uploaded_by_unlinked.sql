-- V25: 첨부 업로더 기록·미연결 첨부 허용 (Track 81-B·D-171)
-- 작성일: 2026-09-17
-- 참조: decisions.md D-171(반품 사진 첨부 — 업로드 시점엔 클레임이 없어 target_id를 채울 수 없고, created_by는 AuditorAware 미구현으로 NULL)

-- 업로더 user id. 소유권 검증(요청자가 올린 파일만 클레임에 연결) 기준. 기존 행(0건)은 NULL 허용.
ALTER TABLE attachment
  ADD COLUMN uploaded_by BIGINT NULL COMMENT '업로더 user id·소유권 검증 기준(D-171)' AFTER display_order;

-- 미연결 첨부(업로드됨·아직 클레임 미연결) = target_type='CLAIM' AND target_id IS NULL. 연결 시 target_id·display_order를 채운다.
-- 기존 조회(findByTargetTypeAndTargetId)는 target_id = ? 등치라 NULL 행을 대상으로 오인하지 않는다.
ALTER TABLE attachment
  MODIFY COLUMN target_id BIGINT NULL COMMENT 'polymorphic 대상 id 논리참조·NULL=미연결 첨부(D-171)';

-- rollback:
--   DELETE FROM attachment WHERE target_id IS NULL;
--   ALTER TABLE attachment MODIFY COLUMN target_id BIGINT NOT NULL COMMENT 'polymorphic 대상 id 논리참조';
--   ALTER TABLE attachment DROP COLUMN uploaded_by;
