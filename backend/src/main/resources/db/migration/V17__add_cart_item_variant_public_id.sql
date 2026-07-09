-- V17: cart_item에 variant_public_id 비정규화 스냅샷 추가 (장바구니 외부 식별키 정상화)
-- 작성일: 2026-07-09
-- 참조: docs/frontend/recon-report-74.md(§5 블로커 — 담기 대상키가 내부 BIGINT PK인데 상세 응답은 var_ public_id만 노출)
-- 목적: 장바구니 외부 계약의 대상 식별키를 내부 variant_id(BIGINT PK)에서 variant_public_id(var_·CHAR(30))로 정상화한다.
--   cart 라인이 외부 식별자를 소유하도록 variant_public_id를 비정규화(스냅샷)로 보유한다. 내부 variant_id FK·
--   UK(user_id, variant_id)는 enrich 조인·중복 담기 판정용으로 그대로 존치한다(이중 보유).
--
-- 설계:
--   charset/collation은 cart_item 테이블 기본(utf8mb4_unicode_ci·V1:542)을 상속하며, product_variant.public_id
--   (CHAR(30)·동일 collation·V1:456)와 정합해 조인 collation 불일치가 없다.
--   백필 UPDATE는 native 조인이라 @SQLRestriction(deleted_at IS NULL) 무관 — soft-delete된 variant를 참조하는
--   dangling cart_item도 스냅샷이 채워진다(dangling 항목도 외부 식별자를 항상 보유·삭제 가능해야 하므로 의도된 동작).
--   3단계(NULL 추가 → 백필 → NOT NULL 강제)로 기존 행 무결성을 보장한다.
ALTER TABLE cart_item ADD COLUMN variant_public_id CHAR(30) NULL COMMENT '담긴 variant의 외부 식별자(var_·비정규화 스냅샷·CRT 외부 대상키)' AFTER variant_id;

UPDATE cart_item ci
  JOIN product_variant pv ON ci.variant_id = pv.id
  SET ci.variant_public_id = pv.public_id;

ALTER TABLE cart_item MODIFY COLUMN variant_public_id CHAR(30) NOT NULL COMMENT '담긴 variant의 외부 식별자(var_·비정규화 스냅샷·CRT 외부 대상키)';
