-- V6: claim 테이블 RETURN 확장 컬럼 추가 (Track 14 PR-1 RETURN·D-98 Q1·Q11)
-- 작성일: 2026-06-30
-- 참조: decisions.md D-98 Q1(picked_up_at 수거 확인)·Q11(previous_order_item_status 스냅샷 원복)
-- 운영 데이터 0건 가정: V1__init.sql claim 시드 INSERT 0건 실측 → 백필 SQL 불요·NOT NULL 즉시 적용

-- D-98 Q1: 수거 확인 시각·Claim.confirmPickup 대상 (STEP 4 소관·본 DDL은 컬럼만)
-- D-98 Q11: 요청 시점 OrderItem 상태 스냅샷·REJECTED 원복용·VARCHAR 무제약(reason_code 일관성·ENUM 동기 비용 기각)
-- 컬럼 위치: 기존 마지막 도메인 컬럼 processed_at 뒤·audit 컬럼 created_at 앞 (V1__init.sql 라인 674-675 정합)
ALTER TABLE claim
  ADD COLUMN picked_up_at DATETIME(6) NULL COMMENT '수거 확인 시각·Q1' AFTER processed_at,
  ADD COLUMN previous_order_item_status VARCHAR(20) NOT NULL COMMENT '요청 시점 OrderItem 상태 스냅샷·REJECTED 원복용·Q11' AFTER picked_up_at;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- ALTER TABLE claim DROP COLUMN previous_order_item_status;
-- ALTER TABLE claim DROP COLUMN picked_up_at;
-- ============================================================
