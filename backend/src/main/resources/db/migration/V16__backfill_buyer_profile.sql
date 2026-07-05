-- V16: 기존 buyer BuyerProfile 백필 (Track 57 BL-8)
-- 작성일: 2026-07-06
-- 참조: docs/track-57/recon-report.md(§5 백필 필요 판정)
-- 목적: UserService.register가 BuyerProfile을 생성하지 않았던 기간(BL-8)에 가입한 buyer 전원에게
--   초기 프로필을 소급 생성한다. 프로필이 없는 buyer는 GradeService.recalculate 단건 500·배치 findAll 순회
--   누락의 원인이 되므로, 배선(UserService) 수정과 동일 릴리스에서 소급 데이터를 정합시킨다.
--
-- 설계:
--   대상 = BUYER role 보유 user 중 buyer_profile 부재 user 전원(role.code='BUYER' JOIN·NOT EXISTS).
--   초기 등급 = SILVER(V15 seed·lifetime 0 → [0,300000) 구간). grade_id는 code subselect로 매핑(id 하드코딩 회피).
--   grade_source = 'AUTO'(신규 가입 배선과 동일 값·GradeSource enum 3값 중 배정). grade_locked_until·grade_updated_at은 NULL.
--   created_by/updated_by는 NULL(시스템 소급). created_at/updated_at은 NOW(6).
--   멱등: NOT EXISTS 가드로 이미 프로필 보유 user는 제외 → 재실행·부분 실행에도 중복 INSERT 없음.
--
-- 주의(백필 범위): soft-delete·탈퇴(withdrawn_at·deleted_at NOT NULL) user도 물리 row가 남아 BUYER role을 보유하면
--   백필 대상에 포함된다. buyer_profile FK(user_id→user)는 ON DELETE RESTRICT라 물리 row 존재 시 INSERT는 성공한다.

INSERT INTO buyer_profile (user_id, grade_id, grade_source, created_at, updated_at)
SELECT u.id,
       (SELECT id FROM buyer_grade WHERE code = 'SILVER'),
       'AUTO',
       NOW(6), NOW(6)
FROM `user` u
JOIN user_role ur ON ur.user_id = u.id
JOIN role r ON ur.role_id = r.id
WHERE r.code = 'BUYER'
  AND NOT EXISTS (SELECT 1 FROM buyer_profile bp WHERE bp.user_id = u.id);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행한다.
-- ※ 본 백필로 생성된 SILVER·AUTO 프로필만 제거(운영자 수동 부여 MANUAL·이벤트 EVENT·재산정 결과는 보존).
--   grade_updated_at IS NULL = 산정·부여 이력이 아직 없는 백필 직후 상태를 식별한다.
--
-- DELETE bp FROM buyer_profile bp
--   JOIN user_role ur ON ur.user_id = bp.user_id
--   JOIN role r ON ur.role_id = r.id
--   WHERE r.code = 'BUYER'
--     AND bp.grade_source = 'AUTO'
--     AND bp.grade_id = (SELECT id FROM buyer_grade WHERE code = 'SILVER')
--     AND bp.grade_updated_at IS NULL;
-- ============================================================
