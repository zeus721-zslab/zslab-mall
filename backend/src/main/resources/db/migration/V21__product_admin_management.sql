-- V21: 관리자 상품 관리 — 판매기간·공급가·상품 단위 수동 품절·이미지 유형 (Track 76)
-- 작성일: 2026-09-16
-- 참조: docs/track-76/recon-report.md·decisions.md D-165
-- 목적: 관리자 상품 목록/수정 화면이 요구하는 (1) 판매 시작/종료 시각 (2) 공급가(표시용·D7 α·정산 무관)
--   (3) 상품 단위 수동 품절 on/off (4) 이미지 유형(갤러리/상세) 컬럼을 신설한다.
--
-- 설계:
--   sale_start_at·sale_end_at = NULL이면 각각 "즉시 판매"·"무기한". 구매 가능 판정(ProductPurchasePolicy)은
--     start ≤ now < end 로 본다(종료 시각은 배타). DATETIME(6)·KST(JVM·hibernate time_zone 정렬·Track 69).
--   supply_price = 표시·참고용 BIGINT(KRW). 정산은 여전히 seller.commission_rate(basis-point·V14·D-133) 기준이며 본 컬럼을 읽지 않는다.
--   is_soldout_manual = 상품 단위 수동 품절. variant 단위 is_soldout_manual(V1)과 별개로 OR 결합한다.
--   image_type = ENUM('GALLERY','DETAIL'). GALLERY는 순서·대표(is_main) 대상 이미지, DETAIL은 상세 영역 이미지.
--     is_main과 혼동을 피하기 위해 'MAIN'이 아닌 'GALLERY'로 명명한다. 기존 행은 DEFAULT 'GALLERY'로 호환한다.
--   soft delete(deleted_at·deleted_by·delete_reason)는 V1:385-387에 이미 존재하므로 추가하지 않는다.
--   인덱스: 관리자 목록의 셀러 필터 + 상태 필터 조합용 (seller_id, status). 판매기간은 NULL 허용 OR 조건이라 인덱스 효용이
--     낮아 두지 않는다(데이터 소량·기조 4).
-- 데이터 안전: 전부 ADD COLUMN(NULL 또는 DEFAULT) — 기존 행 backfill 불요.

ALTER TABLE product
  ADD COLUMN is_soldout_manual TINYINT(1) NOT NULL DEFAULT 0 COMMENT '상품 단위 수동 품절 여부(variant 품절과 OR 결합)' AFTER status,
  ADD COLUMN supply_price BIGINT NULL COMMENT '공급가(KRW·표시용·정산 무관·D-165 D7 α)' AFTER base_price,
  ADD COLUMN sale_start_at DATETIME(6) NULL COMMENT '판매 시작 시각(NULL=즉시)' AFTER thumbnail_url,
  ADD COLUMN sale_end_at DATETIME(6) NULL COMMENT '판매 종료 시각(배타·NULL=무기한)' AFTER sale_start_at,
  ADD KEY ix_product_seller_status (seller_id, status);

ALTER TABLE product_image
  ADD COLUMN image_type ENUM('GALLERY','DETAIL') NOT NULL DEFAULT 'GALLERY' COMMENT '이미지 유형(GALLERY=순서·대표 대상 / DETAIL=상세 영역)' AFTER image_url;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- ALTER TABLE product_image DROP COLUMN image_type;
-- ALTER TABLE product
--   DROP INDEX ix_product_seller_status,
--   DROP COLUMN sale_end_at,
--   DROP COLUMN sale_start_at,
--   DROP COLUMN supply_price,
--   DROP COLUMN is_soldout_manual;
-- ============================================================
