-- V34: product.sale_stop_source — 판매중지 주체 기록 (Track 96-5·D-206·C-08)
-- 작성일: 2026-09-22
-- 참조: docs/track-96/recon-report-seller-sale-status.md §0-1·§STEP 913 · decisions.md D-206
-- 목적: 셀러가 판매중지·재판매를 직접 처리하게 되면서(D-206) "관리자가 제재로 중지한 상품을 셀러가 SALE로 되돌리는" 경로를 막아야 한다.
--   기존에는 중지 주체가 audit_log(actor_role)에만 남아 상태 판정 소스로 쓸 수 없었다(append-only·최신 1건 조회 없음).
--
-- 설계:
--   sale_stop_source = ENUM('ADMIN','SELLER') NULL. 4층위 enum 잠금 (1)DB — Java SaleStopSource·DTO 노출·FE 상수와 1:1.
--   불변식: status = 'STOPPED' ↔ sale_stop_source IS NOT NULL (그 외 상태는 NULL). 앱 레이어(Product.stopSale(source)·resumeSale)가 강제하며
--     DB CHECK는 두지 않는다(기존 IT 시드 9곳이 STOPPED를 source 없이 INSERT·FK_CHECKS 토글로 우회 불가·범위 밖).
--   백필: 기존 STOPPED 행은 전부 관리자 전환 경로(Track 71·관리자 전용 API)로만 생성됐으므로 'ADMIN'으로 채운다(fail-closed —
--     주체 불명은 셀러가 풀 수 없는 쪽으로).
--   V33은 Java 마이그레이션(seller.migration.V33__Encrypt_seller_bank_account_numbers)이 점유해 본 파일은 V34다.
-- 데이터 안전: ADD COLUMN NULL + 조건부 UPDATE — 기존 행 손실 없음. 로컬 실측 STOPPED 0행.

ALTER TABLE product
  ADD COLUMN sale_stop_source ENUM('ADMIN','SELLER') NULL
    COMMENT '판매중지 주체(STOPPED일 때만 NOT NULL·ADMIN 중지는 셀러 재판매 불가·D-206)' AFTER status;

-- 백필: 기존 STOPPED 전건 = 관리자 중지.
UPDATE product SET sale_stop_source = 'ADMIN' WHERE status = 'STOPPED' AND sale_stop_source IS NULL;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- ALTER TABLE product DROP COLUMN sale_stop_source;
-- ============================================================
