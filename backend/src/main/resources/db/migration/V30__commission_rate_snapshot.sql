-- V30: 수수료율 3단 판정(셀러 개별율 → 카테고리율 → 플랫폼 기본율)·주문 시점 order_item 스냅샷 (Track 85 관리자 정산)
-- 작성일: 2026-09-18
-- 참조: docs/track-85/recon-report.md §C · V14(seller.commission_rate·bp 1000=10.00%)
--
-- category.commission_rate  : 카테고리 기본율(NULL=미설정). 셀러 개별율이 없을 때 적용.
-- seller.commission_rate    : 개별 계약율(NULL=계약 없음 → 카테고리/플랫폼 기본율). V14의 NOT NULL DEFAULT 1000을 NULL 허용·DEFAULT 제거로
--                             완화하고, 기본값 1000이던 행은 "개별 계약 없음"으로 해석해 NULL로 되돌린다.
-- order_item.commission_rate: 주문 시점 판정 결과 스냅샷(NOT NULL). 정산은 이 값만 쓴다(사후 셀러·카테고리 율 변경 무영향).
--                             기존 행 백필 = COALESCE(seller 현행율, 상품의 현행 카테고리율, 1000). 주문 시점 카테고리 이력 컬럼은 없어
--                             product.category_id(현행)를 쓴다. 백필 시점엔 category.commission_rate가 전부 NULL·seller는 전부 1000이라
--                             결과는 1000이다. 상수 1000은 설정 settlement.default-commission-rate 기본값과 같아야 한다(application.yml).
-- 순서 엄수: (1)(2) 컬럼 추가 → (3) 백필(seller 율이 아직 1000인 상태) → (4) NOT NULL 전환 → (5)(6) seller 완화·NULL 환원.

-- (1) 카테고리 기본율
ALTER TABLE category
  ADD COLUMN commission_rate INT NULL COMMENT '카테고리 기본 수수료율·basis-point(1000=10.00%)·NULL=미설정(플랫폼 기본율)' AFTER sort_order;

-- (2) 주문 품목 수수료율 스냅샷(백필 전 NULL 허용)
ALTER TABLE order_item
  ADD COLUMN commission_rate INT NULL COMMENT '주문 시점 적용 수수료율 스냅샷·basis-point(셀러 개별율→카테고리율→기본율)' AFTER total_price;

-- (3) 백필: 셀러 현행율 → 상품 현행 카테고리율 → 플랫폼 기본율(1000 = settlement.default-commission-rate 기본값)
UPDATE order_item oi
  JOIN seller s ON s.id = oi.seller_id
  LEFT JOIN product p ON p.id = oi.product_id
  LEFT JOIN category c ON c.id = p.category_id
  SET oi.commission_rate = COALESCE(s.commission_rate, c.commission_rate, 1000);

-- (4) 스냅샷 NOT NULL 전환(신규 주문은 앱이 항상 세팅·DEFAULT 없음)
ALTER TABLE order_item
  MODIFY COLUMN commission_rate INT NOT NULL COMMENT '주문 시점 적용 수수료율 스냅샷·basis-point(셀러 개별율→카테고리율→기본율)';

-- (5) 셀러 개별율 NULL 허용·DEFAULT 제거
ALTER TABLE seller
  MODIFY COLUMN commission_rate INT NULL COMMENT '셀러 개별 계약 수수료율·basis-point·NULL=개별 계약 없음(카테고리/플랫폼 기본율 적용)';

-- (6) V14 기본값(1000)이던 셀러는 개별 계약이 없는 것으로 환원
UPDATE seller SET commission_rate = NULL WHERE commission_rate = 1000;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원·역순)
--
-- UPDATE seller SET commission_rate = 1000 WHERE commission_rate IS NULL;
-- ALTER TABLE seller MODIFY COLUMN commission_rate INT NOT NULL DEFAULT 1000;
-- ALTER TABLE order_item DROP COLUMN commission_rate;
-- ALTER TABLE category DROP COLUMN commission_rate;
-- ============================================================
