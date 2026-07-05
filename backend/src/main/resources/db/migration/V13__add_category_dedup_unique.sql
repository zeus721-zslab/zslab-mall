-- V13: category 중복 방어 UNIQUE (Track 46 카테고리 생성 경로 신설)
-- 작성일: 2026-07-05
-- 참조: decisions.md D-46(예정)·docs/track-46 정찰
-- 목적: 형제 스코프 중복 금지 + soft-delete 재생성 허용 — 같은 parent 밑 동일 display_name 활성 카테고리 1건만 허용.
--
-- 트랩·설계(정찰 실측·MariaDB 11.4 NULL 시맨틱): 단순 UNIQUE (parent_id, display_name, deleted_at)는 무효다.
--   MariaDB UNIQUE는 NULL 포함 행을 중복 판정에서 제외(NULL≠NULL)하므로, API가 만드는 루트(parent_id NULL)·
--   활성(deleted_at NULL) 행 (NULL,'전자',NULL)이 무제한 중복 허용된다 → saveAndFlush 409 가드 미발동.
--   → STORED generated 컬럼으로 우회: 활성 행은 non-null dedup_key로 유니크 강제, soft-delete 시 dedup_key가
--   NULL이 되어 유니크에서 제외돼 동일명 재생성이 허용된다. dedup_key collation은 테이블 기본 utf8mb4_unicode_ci
--   상속 → 대소문자 무구분 유니크(uk_user_email·V1 관습 정합).
--
-- 스코프 한정(중요): 본 트랙 API는 루트 카테고리만 생성하므로 dedup_key는 display_name만으로 구성한다
--   = "활성 display_name 전역 유니크". 루트만 존재하는 현 상태에선 이것이 루트 형제 스코프 유니크와 동치다.
--   parent_id를 포함한 진짜 형제 스코프(같은 parent 밑 동일명 허용)는 여기서 구현하지 않는다 — MariaDB가
--   fk_category_parent(self-FK·V1)의 ON UPDATE CASCADE 때문에 parent_id를 GENERATED 식에 쓰는 것을 금지하기 때문.
--   ⇒ 자식 생성 경로 신설 시: (1) fk_category_parent의 ON UPDATE CASCADE 제거를 선행한 뒤 (2) dedup_key를
--      CONCAT(COALESCE(parent_id,0), ':', display_name)로 확장해 형제 스코프로 재검토할 것.
-- 데이터 안전: category 테이블은 현재 비어 있음(seed·INSERT 경로 부재) → 기존 데이터 충돌 없음.
ALTER TABLE category
  ADD COLUMN dedup_key VARCHAR(200)
    AS (IF(deleted_at IS NULL, display_name, NULL)) STORED
    COMMENT '중복 방어 파생키(활성 행만 non-null·soft-delete 시 NULL·루트 전용 스코프)',
  ADD UNIQUE KEY uk_category_dedup_key (dedup_key);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행한다. UNIQUE는 generated 컬럼에 의존하므로 컬럼보다 먼저 제거한다.
--
-- ALTER TABLE category
--   DROP INDEX uk_category_dedup_key,
--   DROP COLUMN dedup_key;
-- ============================================================
