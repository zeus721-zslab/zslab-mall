-- V28: user 자격증명 갱신 시각·비밀번호 변경 강제 플래그 (Track 84 관리자 회원 관리)
-- 작성일: 2026-09-17
-- 참조: docs/track-84/recon-report.md §B2(토큰 강제 무효화 수단 없음)·§A2(강제 변경 플래그 없음)
-- credentials_changed_at : 비밀번호 변경·임시 비밀번호 발급·탈퇴 시각. JWT iat가 이 시각보다 앞서면 인증 필터가 401로 거부한다
--                          (무상태 JWT의 즉시 무효화 수단). NULL = 무효화 이력 없음(기존 회원 무손상).
-- password_change_required: 관리자 임시 비밀번호 발급 시 1, 본인 비밀번호 변경 시 0. 로그인 응답 플래그로만 노출(BE는 API를 막지 않음).
-- 백필·인덱스 없음(PK 조회만).
ALTER TABLE `user`
  ADD COLUMN credentials_changed_at   DATETIME(6) NULL              COMMENT '자격증명 갱신 시각·이전 발급 JWT 무효화 기준(Track 84)' AFTER password_hash,
  ADD COLUMN password_change_required TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '비밀번호 변경 강제 플래그·임시 비밀번호 발급 시 1(Track 84)' AFTER credentials_changed_at;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- ALTER TABLE `user`
--   DROP COLUMN password_change_required,
--   DROP COLUMN credentials_changed_at;
-- ============================================================
