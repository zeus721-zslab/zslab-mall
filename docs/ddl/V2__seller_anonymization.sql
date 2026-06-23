-- =====================================================================
-- V2__seller_anonymization.sql — Seller 비식별화 흐름 DDL (38 테이블)
-- 트랙        : Seller 비식별화 (feat/seller-anonymization)
-- 입력(SoT)   : decisions.md D-23·invariants.md SLR·state-machine.md §7·db-schema §2.3
-- 대상 DBMS   : MariaDB 10.x / 스키마 zslab_mall
-- V1 기준 변경 :
--   (1) CREATE withdrawn_seller (종료 판매자 아카이브·ARCHIVE·withdrawn_user 패턴 준용)
--   (2) ALTER seller: company_name·ceo_name NOT NULL → NULL (비식별화 대상·B-d3)
--   · account_number 비식별화 = 암호화 키 폐기(B-d4) → DDL 무변경
--   · contact_email·contact_phone·business_no = V1 이미 NULL → DDL 무변경
--   · seller_bank_account·settlement = DDL 무변경 (B-d4·B-d7)
-- rollback    : DROP TABLE withdrawn_seller + seller 두 컬럼 NOT NULL 복원
--               (단 NULL 데이터 존재 시 복원 불가·보상 마이그레이션 필요)
-- 규약        : V1 계승 (charset utf8mb4_unicode_ci·FK RESTRICT/CASCADE·
--               audit 4·시간 DATETIME(6) UTC·FK 자식 인덱스 InnoDB 자동 생성)
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ---------------------------------------------------------------------
-- (1) withdrawn_seller — 종료 판매자 아카이브 (withdrawn_user 패턴 준용)
-- ---------------------------------------------------------------------
CREATE TABLE withdrawn_seller (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  original_seller_id    BIGINT       NOT NULL COMMENT '종료 전 Seller.id·FK(N:1)',
  terminate_reason      VARCHAR(255) NULL     COMMENT '종료 사유(탈퇴·강제 종료·승인 거부)',
  legal_retention_until DATETIME(6)  NULL     COMMENT '법정 보관 만료 시각',
  anonymized_at         DATETIME(6)  NULL     COMMENT '비식별화 완료 시각',
  created_at            DATETIME(6)  NOT NULL,
  created_by            BIGINT       NULL,
  updated_at            DATETIME(6)  NOT NULL,
  updated_by            BIGINT       NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_withdrawn_seller_seller FOREIGN KEY (original_seller_id) REFERENCES seller (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='종료 판매자 아카이브(SLR 종속·ARCHIVE·D-23·SLR-6)';

-- ---------------------------------------------------------------------
-- (2) seller — company_name·ceo_name 비식별화 대상 NULL 허용 (B-d3·D-23)
--     활성 판매자 필수값은 Service 검증으로 강제(User.email/name/phone 동일 패턴)
-- ---------------------------------------------------------------------
ALTER TABLE seller
  MODIFY COLUMN company_name VARCHAR(100) NULL COMMENT '상호·비식별화 시 NULL(D-23)',
  MODIFY COLUMN ceo_name     VARCHAR(50)  NULL COMMENT '대표자명·비식별화 시 NULL(D-23)';
