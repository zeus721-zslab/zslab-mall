-- V10: user 자격증명 컬럼 추가 (Track 33)
-- password_hash: BCrypt 해시(60자)·NULL 허용
-- NULL = 아직 로그인 자격증명 미생성 상태 (기존 레코드 무손상)
-- 로그인 가능 조건: password_hash IS NOT NULL AND deleted_at IS NULL AND withdrawn_at IS NULL
ALTER TABLE `user`
  ADD COLUMN password_hash VARCHAR(60) NULL COMMENT 'BCrypt 해시·NULL=자격증명 미생성(Track 33)' AFTER phone;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- ALTER TABLE `user` DROP COLUMN password_hash;
-- ============================================================
