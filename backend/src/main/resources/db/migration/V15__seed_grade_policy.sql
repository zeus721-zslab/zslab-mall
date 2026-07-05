-- V15: 구매자 등급 마스터·정책 시드 (Track 51 Grade 산정 Phase 1)
-- 작성일: 2026-07-05
-- 참조: docs/track-51/recon-report.md(R2 시드 전무·R2-c)·decisions.md GRD-1~3(invariants §2.3)
-- 목적: 등급 산정이 참조할 (1) buyer_grade 3등급 마스터 (2) grade_policy 3구간 활성 정책을 심는다.
--   등급 마스터·정책은 환경 불변 reference data → Flyway SQL seed (role seed V11 패턴 준용).
--
-- 설계(확정·옵션 A):
--   구간은 반개구간 [min_amount, max_amount) — 인접 등급의 max == 다음 등급의 min으로 연결(경계값은 상위 등급 귀속).
--   effective_to = '9999-12-31 23:59:59.999999' = 무기한 상한 센티넬(effective_to NOT NULL 대응·V1 DDL NOT NULL 유지).
--     → NULL 대신 far-future 값으로 "무기한 활성"을 표현해 활성 정책 조회 쿼리의 IS NULL 분기를 제거한다.
--   grade_id는 buyer_grade.code subselect로 매핑(AUTO_INCREMENT id 하드코딩 회피).
--   GRD-3 CHECK(min_amount <= max_amount) 전 행 충족(0<=300000·300000<=1000000·1000000<=BIGINT MAX).
--
-- 순서: buyer_grade 먼저 → grade_policy(FK grade_id 참조). created_by/updated_by는 NULL(시스템 시드).

INSERT INTO buyer_grade (code, name, created_at, updated_at) VALUES
  ('SILVER',   '실버',     NOW(6), NOW(6)),
  ('GOLD',     '골드',     NOW(6), NOW(6)),
  ('PLATINUM', '플래티넘', NOW(6), NOW(6));

INSERT INTO grade_policy
  (grade_id, min_amount, max_amount, discount_rate, point_rate, effective_from, effective_to, version, is_active, created_at, updated_at) VALUES
  ((SELECT id FROM buyer_grade WHERE code = 'SILVER'),
     0,       300000,              0, 1, '2026-01-01 00:00:00.000000', '9999-12-31 23:59:59.999999', 1, 1, NOW(6), NOW(6)),
  ((SELECT id FROM buyer_grade WHERE code = 'GOLD'),
     300000,  1000000,            3, 2, '2026-01-01 00:00:00.000000', '9999-12-31 23:59:59.999999', 1, 1, NOW(6), NOW(6)),
  ((SELECT id FROM buyer_grade WHERE code = 'PLATINUM'),
     1000000, 9223372036854775807, 5, 3, '2026-01-01 00:00:00.000000', '9999-12-31 23:59:59.999999', 1, 1, NOW(6), NOW(6));

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행한다(역순: 정책 제거 → 마스터 제거).
-- ※ buyer_grade 삭제는 buyer_profile.grade_id·grade_policy.grade_id FK 참조가 없을 때만 성공.
--
-- DELETE FROM grade_policy WHERE grade_id IN (SELECT id FROM buyer_grade WHERE code IN ('SILVER','GOLD','PLATINUM'));
-- DELETE FROM buyer_grade WHERE code IN ('SILVER','GOLD','PLATINUM');
-- ============================================================
