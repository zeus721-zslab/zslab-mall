-- V11: role reference data seed + code UNIQUE 제약 (Track 34)
-- role 6값은 환경 불변 reference data → Flyway SQL seed로 심는다.
-- uk_role_code: V1에 없던 role.code UNIQUE 보강 → seed 중복 방지·findByCode 최대 1건 보장.
-- 순서: UNIQUE 제약 먼저 → seed INSERT (중복 code는 제약 위반으로 즉시 실패시켜 방지).
ALTER TABLE role
  ADD UNIQUE KEY uk_role_code (code);

INSERT INTO role (code, name, created_at, updated_at) VALUES
  ('SUPER_ADMIN',    '슈퍼 관리자',   NOW(6), NOW(6)),
  ('ADMIN_OPERATOR', '운영 관리자',   NOW(6), NOW(6)),
  ('BUYER',          '구매자',        NOW(6), NOW(6)),
  ('SELLER_OWNER',   '판매자 대표',   NOW(6), NOW(6)),
  ('SELLER_MANAGER', '판매자 매니저', NOW(6), NOW(6)),
  ('SELLER_STAFF',   '판매자 담당자', NOW(6), NOW(6));

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행 (역순: seed 제거 → 제약 제거):
-- ※ seed 삭제는 user_role·seller_user·role_permission FK 참조가 없을 때만 성공.
--    참조 존재 시 해당 매핑 정리 후 실행.
--
-- DELETE FROM role WHERE code IN
--   ('SUPER_ADMIN','ADMIN_OPERATOR','BUYER','SELLER_OWNER','SELLER_MANAGER','SELLER_STAFF');
-- ALTER TABLE role DROP INDEX uk_role_code;
-- ============================================================
