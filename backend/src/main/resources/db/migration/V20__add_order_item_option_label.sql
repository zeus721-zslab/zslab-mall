-- V20: order_item 옵션명 스냅샷 컬럼 추가 (Track 75·FE-21 옵션명 표시)
-- 작성일: 2026-09-16
-- 참조: docs/track-75/recon-report.md·decisions.md D-164
-- 목적: 주문 시점의 옵션 라벨("색상: 블랙 / 사이즈: M")을 order_item에 박제해, 이후 옵션 그룹·값이 수정·삭제돼도
--   과거 주문 상세 표기가 변하지 않게 한다(α 주문 시점 스냅샷·표시 전용 포맷 문자열 1컬럼).
--
-- 설계: 그룹명 VARCHAR(50)+값 VARCHAR(100) 최대 3조 + 구분자 → VARCHAR(500)로 여유 확보.
--   옵션 없는 단순상품(DEFAULT sentinel)·옵션값 미해소는 NULL. 기존 행은 backfill 없이 NULL 유지(주문 상세 미표시).
ALTER TABLE order_item
  ADD COLUMN option_label VARCHAR(500) NULL COMMENT '주문 시점 옵션 라벨 스냅샷("그룹: 값 / …")·옵션 없음은 NULL·표시 전용' AFTER total_price;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- ALTER TABLE order_item DROP COLUMN option_label;
-- ============================================================
