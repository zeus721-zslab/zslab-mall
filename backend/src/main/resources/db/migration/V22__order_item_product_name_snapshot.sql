-- V22: order_item 상품명 스냅샷 컬럼 추가 (Track 76 관리자 상품 수정·삭제 대비)
-- 작성일: 2026-09-16
-- 참조: docs/track-76/recon-report.md B-2·decisions.md D-165
-- 목적: 주문 조회의 상품명이 product.name 재조회(enrich)라서 관리자가 상품명을 수정하거나 상품을 soft-delete하면
--   과거 주문 표기가 바뀌거나(수정) 사라진다(삭제·@SQLRestriction). 주문 시점 상품명을 order_item에 박제해
--   option_label(V20)·unit_price(V1)와 같은 "주문 시점 스냅샷" 계약으로 통일한다.
--
-- 설계: VARCHAR(200) = product.name과 동일 길이. 기존 행은 product 조인으로 backfill 후 NOT NULL 전환한다.
--   backfill은 native 조인이라 soft-delete된 product(deleted_at IS NOT NULL)도 포함되며, fk_order_item_product RESTRICT로
--   product 행은 항상 존재하므로 NULL 잔존 행이 없다(V17 cart_item.variant_public_id 백필과 동일 절차).
-- 데이터 안전: ADD NULL → UPDATE JOIN → MODIFY NOT NULL 3단계. 실패 시 NULL 컬럼만 남아 앱 기동엔 영향 없음(엔티티 nullable=false는
--   INSERT 경로만 강제).

ALTER TABLE order_item
  ADD COLUMN product_name VARCHAR(200) NULL COMMENT '주문 시점 상품명 스냅샷(표시 전용·이후 상품명 수정·삭제와 무관)' AFTER seller_id;

UPDATE order_item oi
  JOIN product p ON oi.product_id = p.id
  SET oi.product_name = p.name;

ALTER TABLE order_item
  MODIFY COLUMN product_name VARCHAR(200) NOT NULL COMMENT '주문 시점 상품명 스냅샷(표시 전용·이후 상품명 수정·삭제와 무관)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
--
-- ALTER TABLE order_item DROP COLUMN product_name;
-- ============================================================
