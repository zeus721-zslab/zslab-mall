-- =====================================================================
-- V1__init.sql — zslab_mall 초기 스키마 (37 테이블)
-- 트랙        : DDL 작성 (feat/ddl-v1-init)
-- 입력(SoT)   : docs/ddl/RECON.md (정찰 §1~§7 사본)
-- 결정        : docs/ddl/decisions.md (audit 5분류·코멘트 정책·②③④)
-- 대상 DBMS   : MariaDB 10.x / 스키마 zslab_mall
-- 규약        : 내부 PK BIGINT AUTO_INCREMENT (집계 2·공유 PK 1 예외)
--               public_id CHAR(30) NOT NULL UK (12 테이블 한정)
--               금액 BIGINT (KRW 정수)·시간 DATETIME(6) UTC (DEFAULT 미지정)
--               charset utf8mb4 / utf8mb4_unicode_ci (테이블 레벨·컬럼 상속)
--               BOOLEAN→TINYINT(1)·JSON→LONGTEXT+CHECK(JSON_VALID)
--               FK ON DELETE RESTRICT ON UPDATE CASCADE
--               FK 자식 컬럼 인덱스는 InnoDB 자동 생성에 의존 (별도 선언 안 함)
--               명명 fk_/uk_/ix_/chk_ (PK는 MariaDB 강제 'PRIMARY')
-- 위상정렬    : 1 user → 37 notification_log (FK 부모 선생성·사이클 0)
-- 예약어      : `order`·`user` 백틱 처리
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ---------------------------------------------------------------------
-- 1. user
-- ---------------------------------------------------------------------
CREATE TABLE `user` (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  public_id       CHAR(30)     NOT NULL COMMENT 'ULID+prefix usr_·외부 노출 식별자',
  email           VARCHAR(254) NULL     COMMENT '로그인 이메일·UK(USR-1)·탈퇴 비식별화 시 NULL(D-22)',
  name            VARCHAR(50)  NULL     COMMENT '회원명·비식별화 대상(탈퇴 시 NULL)',
  phone           VARCHAR(20)  NULL     COMMENT '휴대폰·비식별화 대상(탈퇴 시 HASH/NULL)',
  withdrawn_at    DATETIME(6)  NULL     COMMENT '탈퇴 요청 시각',
  anonymized_at   DATETIME(6)  NULL     COMMENT '비식별화 완료 시각',
  created_at      DATETIME(6)  NOT NULL,
  created_by      BIGINT       NULL,
  updated_at      DATETIME(6)  NOT NULL,
  updated_by      BIGINT       NULL,
  deleted_at      DATETIME(6)  NULL,
  deleted_by      BIGINT       NULL,
  delete_reason   VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_public_id (public_id),
  UNIQUE KEY uk_user_email (email),
  KEY ix_user_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원(USR Aggregate Root·SOFT·public_id usr_)';

-- ---------------------------------------------------------------------
-- 2. role
-- ---------------------------------------------------------------------
CREATE TABLE role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  code        ENUM('SUPER_ADMIN','ADMIN_OPERATOR','BUYER','SELLER_OWNER','SELLER_MANAGER','SELLER_STAFF') NOT NULL COMMENT '역할 코드·A#2 잠금(AUTH-3)',
  name        VARCHAR(50) NOT NULL COMMENT '역할 표시명',
  created_at  DATETIME(6) NOT NULL,
  updated_at  DATETIME(6) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='역할(Auth Aggregate Root·HARD·시드성)';

-- ---------------------------------------------------------------------
-- 3. permission
-- ---------------------------------------------------------------------
CREATE TABLE permission (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  code        VARCHAR(50)  NOT NULL COMMENT '권한 코드',
  name        VARCHAR(200) NOT NULL COMMENT '권한 표시명',
  created_at  DATETIME(6)  NOT NULL,
  updated_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='권한(Auth 종속·HARD·시드성)';

-- ---------------------------------------------------------------------
-- 4. buyer_grade
-- ---------------------------------------------------------------------
CREATE TABLE buyer_grade (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  code        ENUM('SILVER','GOLD','PLATINUM') NOT NULL COMMENT '등급 코드·A#3 잠금(GRD-2)',
  name        VARCHAR(50) NOT NULL COMMENT '등급 표시명',
  created_at  DATETIME(6) NOT NULL,
  created_by  BIGINT      NULL,
  updated_at  DATETIME(6) NOT NULL,
  updated_by  BIGINT      NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='구매자 등급(GRD Aggregate Root·SOFT)';

-- ---------------------------------------------------------------------
-- 5. seller
-- ---------------------------------------------------------------------
CREATE TABLE seller (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  public_id       CHAR(30)     NOT NULL COMMENT 'ULID+prefix slr_',
  company_name    VARCHAR(100) NOT NULL COMMENT '상호',
  business_no     VARCHAR(20)  NULL     COMMENT '사업자등록번호·UK(SLR-1)·재등록 비식별화 시 NULL(D-22)',
  ceo_name        VARCHAR(50)  NOT NULL COMMENT '대표자명',
  contact_email   VARCHAR(254) NULL     COMMENT '담당자 이메일',
  contact_phone   VARCHAR(20)  NULL     COMMENT '담당자 연락처',
  status          ENUM('PENDING','ACTIVE','SUSPENDED','TERMINATED') NOT NULL COMMENT '판매자 상태·B/SELLER_STATUS(SLR-4)',
  created_at      DATETIME(6)  NOT NULL,
  created_by      BIGINT       NULL,
  updated_at      DATETIME(6)  NOT NULL,
  updated_by      BIGINT       NULL,
  deleted_at      DATETIME(6)  NULL,
  deleted_by      BIGINT       NULL,
  delete_reason   VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_seller_public_id (public_id),
  UNIQUE KEY uk_seller_business_no (business_no),
  KEY ix_seller_status (status),
  KEY ix_seller_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='판매자(SLR Aggregate Root·SOFT·public_id slr_)';

-- ---------------------------------------------------------------------
-- 6. code_group
-- ---------------------------------------------------------------------
CREATE TABLE code_group (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  code         VARCHAR(50)  NOT NULL COMMENT '코드 그룹 코드(예: ORDER_STATUS)',
  name         VARCHAR(100) NOT NULL COMMENT '코드 그룹명',
  description  TEXT         NULL     COMMENT '설명',
  created_at   DATETIME(6)  NOT NULL,
  created_by   BIGINT       NULL,
  updated_at   DATETIME(6)  NOT NULL,
  updated_by   BIGINT       NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='코드 그룹(COD Aggregate Root·SOFT(is_system=F))';

-- ---------------------------------------------------------------------
-- 7. category (self-FK)
-- ---------------------------------------------------------------------
CREATE TABLE category (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  parent_id       BIGINT       NULL     COMMENT '상위 카테고리·self FK(CAT-1)·루트는 NULL',
  display_name    VARCHAR(200) NOT NULL COMMENT '카테고리 표시명',
  depth           INT          NOT NULL COMMENT '계층 깊이(CAT-2)',
  sort_order      INT          NOT NULL COMMENT '정렬 순서',
  created_at      DATETIME(6)  NOT NULL,
  created_by      BIGINT       NULL,
  updated_at      DATETIME(6)  NOT NULL,
  updated_by      BIGINT       NULL,
  deleted_at      DATETIME(6)  NULL,
  deleted_by      BIGINT       NULL,
  delete_reason   VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_category_deleted_at (deleted_at),
  CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='카테고리(CAT Aggregate Root·SOFT)';

-- ---------------------------------------------------------------------
-- 8. withdrawn_user
-- ---------------------------------------------------------------------
CREATE TABLE withdrawn_user (
  id                    BIGINT       NOT NULL AUTO_INCREMENT,
  original_user_id      BIGINT       NOT NULL COMMENT '탈퇴 전 User.id·FK(N:1)',
  withdraw_reason       VARCHAR(255) NULL     COMMENT '탈퇴 사유',
  legal_retention_until DATETIME(6)  NULL     COMMENT '법정 보관 만료 시각',
  anonymized_at         DATETIME(6)  NULL     COMMENT '비식별화 완료 시각',
  created_at            DATETIME(6)  NOT NULL,
  created_by            BIGINT       NULL,
  updated_at            DATETIME(6)  NOT NULL,
  updated_by            BIGINT       NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_withdrawn_user_user FOREIGN KEY (original_user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='탈퇴 회원 아카이브(USR 종속·ARCHIVE)';

-- ---------------------------------------------------------------------
-- 9. buyer_profile (공유 PK = user_id)
-- ---------------------------------------------------------------------
CREATE TABLE buyer_profile (
  user_id            BIGINT      NOT NULL COMMENT 'User.id 공유 PK·FK(1:1)',
  grade_id           BIGINT      NOT NULL COMMENT 'FK→buyer_grade(N:1)',
  grade_source       ENUM('AUTO','MANUAL','EVENT') NOT NULL COMMENT '등급 산정 출처·A#1',
  grade_locked_until DATETIME(6) NULL     COMMENT '등급 고정 만료 시각',
  grade_updated_at   DATETIME(6) NULL     COMMENT '등급 변경 시각',
  created_at         DATETIME(6) NOT NULL,
  created_by         BIGINT      NULL,
  updated_at         DATETIME(6) NOT NULL,
  updated_by         BIGINT      NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_buyer_profile_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_buyer_profile_grade FOREIGN KEY (grade_id) REFERENCES buyer_grade (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='구매자 프로필(USR 종속·SOFT 상속·user_id 공유 PK)';

-- ---------------------------------------------------------------------
-- 10. user_address
-- ---------------------------------------------------------------------
CREATE TABLE user_address (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  user_id         BIGINT       NOT NULL COMMENT 'FK→user(N:1)',
  is_default      TINYINT(1)   NOT NULL COMMENT '기본 배송지 여부',
  address_label   VARCHAR(50)  NULL     COMMENT '배송지 별칭',
  recipient_name  VARCHAR(50)  NOT NULL COMMENT '수령인명',
  recipient_phone VARCHAR(20)  NOT NULL COMMENT '수령인 연락처',
  zonecode        VARCHAR(10)  NOT NULL COMMENT '우편번호(④ VARCHAR(10))',
  address_road    VARCHAR(200) NOT NULL COMMENT '도로명 주소',
  address_jibun   VARCHAR(200) NULL     COMMENT '지번 주소',
  address_detail  VARCHAR(200) NULL     COMMENT '상세 주소',
  created_at      DATETIME(6)  NOT NULL,
  created_by      BIGINT       NULL,
  updated_at      DATETIME(6)  NOT NULL,
  updated_by      BIGINT       NULL,
  deleted_at      DATETIME(6)  NULL,
  deleted_by      BIGINT       NULL,
  delete_reason   VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY ix_user_address_deleted_at (deleted_at),
  CONSTRAINT fk_user_address_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='배송지(USR 종속·SOFT)';

-- ---------------------------------------------------------------------
-- 11. user_role (N:M)
-- ---------------------------------------------------------------------
CREATE TABLE user_role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL COMMENT 'FK→user',
  role_id     BIGINT      NOT NULL COMMENT 'FK→role',
  created_at  DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id),
  CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='회원-역할 매핑(Auth 종속·HARD·N:M)';

-- ---------------------------------------------------------------------
-- 12. role_permission (N:M)
-- ---------------------------------------------------------------------
CREATE TABLE role_permission (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  role_id        BIGINT      NOT NULL COMMENT 'FK→role',
  permission_id  BIGINT      NOT NULL COMMENT 'FK→permission',
  created_at     DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_permission (role_id, permission_id),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='역할-권한 매핑(Auth 종속·HARD·N:M)';

-- ---------------------------------------------------------------------
-- 13. seller_user
-- ---------------------------------------------------------------------
CREATE TABLE seller_user (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  seller_id   BIGINT      NOT NULL COMMENT 'FK→seller',
  user_id     BIGINT      NOT NULL COMMENT 'FK→user',
  role_id     BIGINT      NOT NULL COMMENT 'FK→role(판매자 내 역할)',
  created_at  DATETIME(6) NOT NULL,
  created_by  BIGINT      NULL,
  updated_at  DATETIME(6) NOT NULL,
  updated_by  BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_seller_user (seller_id, user_id),
  CONSTRAINT fk_seller_user_seller FOREIGN KEY (seller_id) REFERENCES seller (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_seller_user_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_seller_user_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='판매자 구성원(Seller 귀속·SOFT 상속)';

-- ---------------------------------------------------------------------
-- 14. grade_policy
-- ---------------------------------------------------------------------
CREATE TABLE grade_policy (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  grade_id        BIGINT      NOT NULL COMMENT 'FK→buyer_grade(N:1)',
  min_amount      BIGINT      NOT NULL COMMENT '등급 최소 누적구매액',
  max_amount      BIGINT      NOT NULL COMMENT '등급 최대 누적구매액',
  discount_rate   INT         NOT NULL COMMENT '할인율(정수 basis)',
  point_rate      INT         NOT NULL COMMENT '적립율(정수 basis)',
  effective_from  DATETIME(6) NOT NULL COMMENT '정책 적용 시작',
  effective_to    DATETIME(6) NOT NULL COMMENT '정책 적용 종료',
  version         INT         NOT NULL COMMENT '정책 버전',
  is_active       TINYINT(1)  NOT NULL COMMENT '활성 여부',
  created_at      DATETIME(6) NOT NULL,
  created_by      BIGINT      NULL,
  updated_at      DATETIME(6) NOT NULL,
  updated_by      BIGINT      NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_grade_policy_grade FOREIGN KEY (grade_id) REFERENCES buyer_grade (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT chk_grade_policy_amount CHECK (min_amount <= max_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='등급 정책(GRD 종속·SOFT 상속)';

-- ---------------------------------------------------------------------
-- 15. buyer_purchase_aggregate (단일 PK = buyer_id·Read Model)
-- ---------------------------------------------------------------------
CREATE TABLE buyer_purchase_aggregate (
  buyer_id                 BIGINT      NOT NULL COMMENT 'User.id 논리참조·PK(FK 미적용 ②)',
  lifetime_purchase_amount BIGINT      NOT NULL COMMENT '생애 누적 구매액',
  last_ordered_at          DATETIME(6) NULL     COMMENT '최근 주문 시각',
  updated_at               DATETIME(6) NOT NULL COMMENT '집계 갱신 시각(이벤트 핸들러 E6)',
  PRIMARY KEY (buyer_id),
  KEY ix_buyer_purchase_aggregate_last_ordered (last_ordered_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='구매 집계 Read Model(ARCHIVE·집계·FK 미적용)';

-- ---------------------------------------------------------------------
-- 16. seller_bank_account
-- ---------------------------------------------------------------------
CREATE TABLE seller_bank_account (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  seller_id       BIGINT       NOT NULL COMMENT 'FK→seller(N:1)',
  bank_code       VARCHAR(20)  NOT NULL COMMENT '은행 코드',
  account_number  VARCHAR(255) NOT NULL COMMENT '계좌번호·AES 암호화(SLR-2)',
  account_holder  VARCHAR(50)  NOT NULL COMMENT '예금주',
  is_primary      TINYINT(1)   NOT NULL COMMENT '주 정산계좌 여부(SLR-3)',
  verified_at     DATETIME(6)  NULL     COMMENT '계좌 인증 시각',
  status          ENUM('PENDING','VERIFIED','REJECTED') NOT NULL COMMENT '인증 상태·A#4',
  created_at      DATETIME(6)  NOT NULL,
  created_by      BIGINT       NULL,
  updated_at      DATETIME(6)  NOT NULL,
  updated_by      BIGINT       NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_seller_bank_account_seller FOREIGN KEY (seller_id) REFERENCES seller (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='판매자 정산계좌(Seller 종속·ARCHIVE)';

-- ---------------------------------------------------------------------
-- 17. settlement
-- ---------------------------------------------------------------------
CREATE TABLE settlement (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  seller_id        BIGINT      NOT NULL COMMENT 'FK→seller(N:1)',
  bank_account_id  BIGINT      NOT NULL COMMENT 'FK→seller_bank_account·스냅샷(STL-3)',
  period_start     DATETIME(6) NOT NULL COMMENT '정산 기간 시작',
  period_end       DATETIME(6) NOT NULL COMMENT '정산 기간 종료',
  gross_amount     BIGINT      NOT NULL COMMENT '총 매출',
  fee_amount       BIGINT      NOT NULL COMMENT '수수료',
  refund_amount    BIGINT      NOT NULL COMMENT '환불액',
  net_amount       BIGINT      NOT NULL COMMENT '정산액=gross-fee-refund(STL-1)',
  status           ENUM('PENDING','CONFIRMED','PAID') NOT NULL COMMENT '정산 상태·A#5(STL-2)',
  paid_at          DATETIME(6) NULL     COMMENT '지급 완료 시각',
  created_at       DATETIME(6) NOT NULL,
  created_by       BIGINT      NULL,
  updated_at       DATETIME(6) NOT NULL,
  updated_by       BIGINT      NULL,
  PRIMARY KEY (id),
  KEY ix_settlement_seller_status (seller_id, status),
  KEY ix_settlement_period (period_start, period_end),
  CONSTRAINT fk_settlement_seller FOREIGN KEY (seller_id) REFERENCES seller (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_settlement_bank_account FOREIGN KEY (bank_account_id) REFERENCES seller_bank_account (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='정산(STL Aggregate Root·ARCHIVE)';

-- ---------------------------------------------------------------------
-- 18. seller_sales_daily (복합 PK·Read Model)
-- ---------------------------------------------------------------------
CREATE TABLE seller_sales_daily (
  seller_id      BIGINT      NOT NULL COMMENT 'Seller.id 논리참조·복합 PK(FK 미적용 ②)',
  sale_date      DATE        NOT NULL COMMENT '집계 일자·복합 PK',
  order_count    INT         NOT NULL COMMENT '주문 건수',
  gross_amount   BIGINT      NOT NULL COMMENT '총 매출',
  refund_amount  BIGINT      NOT NULL COMMENT '환불액',
  net_amount     BIGINT      NOT NULL COMMENT '순 매출',
  updated_at     DATETIME(6) NOT NULL COMMENT '집계 갱신 시각(배치)',
  PRIMARY KEY (seller_id, sale_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='판매자 일별 집계 Read Model(ARCHIVE·집계·FK 미적용)';

-- ---------------------------------------------------------------------
-- 19. code
-- ---------------------------------------------------------------------
CREATE TABLE code (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  group_id     BIGINT       NOT NULL COMMENT 'FK→code_group(N:1)',
  code         VARCHAR(50)  NOT NULL COMMENT '코드 값',
  label        VARCHAR(100) NOT NULL COMMENT '표시 라벨(운영자 편집)',
  display_order INT         NOT NULL COMMENT '정렬 순서',
  is_active    TINYINT(1)   NOT NULL COMMENT '활성 여부',
  is_system    TINYINT(1)   NOT NULL COMMENT '시스템 코드 여부·삭제 금지(COD-1)',
  created_at   DATETIME(6)  NOT NULL,
  created_by   BIGINT       NULL,
  updated_at   DATETIME(6)  NOT NULL,
  updated_by   BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_code_group_code (group_id, code),
  CONSTRAINT fk_code_group FOREIGN KEY (group_id) REFERENCES code_group (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='코드(COD 종속·SOFT(is_system=F))';

-- ---------------------------------------------------------------------
-- 20. product
-- ---------------------------------------------------------------------
CREATE TABLE product (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  public_id       CHAR(30)      NOT NULL COMMENT 'ULID+prefix prd_',
  seller_id       BIGINT        NOT NULL COMMENT 'FK→seller(N:1·PRD-1)',
  category_id     BIGINT        NOT NULL COMMENT 'FK→category(N:1)',
  name            VARCHAR(200)  NOT NULL COMMENT '상품명',
  description     LONGTEXT      NULL     COMMENT '상품 상세 설명',
  status          ENUM('DRAFT','PENDING','APPROVED','REJECTED','SALE','HIDDEN','STOPPED') NOT NULL COMMENT '상품 상태·A#6',
  base_price      BIGINT        NOT NULL COMMENT '기본 판매가(KRW)',
  thumbnail_url   VARCHAR(2048) NULL     COMMENT '대표 이미지 URL',
  created_at      DATETIME(6)   NOT NULL,
  created_by      BIGINT        NULL,
  updated_at      DATETIME(6)   NOT NULL,
  updated_by      BIGINT        NULL,
  deleted_at      DATETIME(6)   NULL,
  deleted_by      BIGINT        NULL,
  delete_reason   VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_public_id (public_id),
  KEY ix_product_category_status (category_id, status),
  KEY ix_product_status (status),
  KEY ix_product_deleted_at (deleted_at),
  CONSTRAINT fk_product_seller FOREIGN KEY (seller_id) REFERENCES seller (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품(PRD Aggregate Root·SOFT·public_id prd_)';

-- ---------------------------------------------------------------------
-- 21. product_image
-- ---------------------------------------------------------------------
CREATE TABLE product_image (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  product_id      BIGINT        NOT NULL COMMENT 'FK→product(N:1)',
  image_url       VARCHAR(2048) NOT NULL COMMENT '이미지 URL',
  display_order   INT           NOT NULL COMMENT '정렬 순서',
  is_main         TINYINT(1)    NOT NULL COMMENT '대표 이미지 여부',
  created_at      DATETIME(6)   NOT NULL,
  created_by      BIGINT        NULL,
  updated_at      DATETIME(6)   NOT NULL,
  updated_by      BIGINT        NULL,
  deleted_at      DATETIME(6)   NULL,
  deleted_by      BIGINT        NULL,
  delete_reason   VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  KEY ix_product_image_deleted_at (deleted_at),
  CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 이미지(PRD 종속·SOFT)';

-- ---------------------------------------------------------------------
-- 22. product_option_group
-- ---------------------------------------------------------------------
CREATE TABLE product_option_group (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  product_id     BIGINT      NOT NULL COMMENT 'FK→product(N:1)·상품당 최대 3(PRD-4)',
  name           VARCHAR(50) NOT NULL COMMENT '옵션 그룹명(예: 색상)',
  display_order  INT         NOT NULL COMMENT '정렬 순서',
  created_at     DATETIME(6) NOT NULL,
  created_by     BIGINT      NULL,
  updated_at     DATETIME(6) NOT NULL,
  updated_by     BIGINT      NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_product_option_group_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 옵션 그룹(PRD 종속·SOFT 상속)';

-- ---------------------------------------------------------------------
-- 23. product_option_value
-- ---------------------------------------------------------------------
CREATE TABLE product_option_value (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  option_group_id  BIGINT       NOT NULL COMMENT 'FK→product_option_group(N:1)',
  value            VARCHAR(100) NOT NULL COMMENT '옵션값(예: 빨강)',
  display_order    INT          NOT NULL COMMENT '정렬 순서',
  created_at       DATETIME(6)  NOT NULL,
  created_by       BIGINT       NULL,
  updated_at       DATETIME(6)  NOT NULL,
  updated_by       BIGINT       NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_product_option_value_group FOREIGN KEY (option_group_id) REFERENCES product_option_group (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 옵션값(PRD 종속·SOFT 상속)';

-- ---------------------------------------------------------------------
-- 24. product_variant
-- ---------------------------------------------------------------------
CREATE TABLE product_variant (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  public_id          CHAR(30)     NOT NULL COMMENT 'ULID+prefix var_',
  product_id         BIGINT       NOT NULL COMMENT 'FK→product(N:1·PRD-3)',
  variant_code       VARCHAR(50)  NOT NULL COMMENT 'SKU 변형 코드',
  seller_sku         VARCHAR(100) NULL     COMMENT '판매자 SKU',
  barcode            VARCHAR(100) NULL     COMMENT '바코드',
  additional_price   BIGINT       NOT NULL COMMENT '옵션 추가금(KRW)',
  status             ENUM('SALE','HIDDEN','STOPPED') NOT NULL COMMENT '변형 상태·A#7',
  is_soldout_manual  TINYINT(1)   NOT NULL COMMENT '수동 품절 여부',
  display_order      INT          NOT NULL COMMENT '정렬 순서',
  option1_value_id   BIGINT       NOT NULL COMMENT 'FK→product_option_value·필수(PRD-5)',
  option2_value_id   BIGINT       NULL     COMMENT 'FK→product_option_value·선택',
  option3_value_id   BIGINT       NULL     COMMENT 'FK→product_option_value·선택',
  created_at         DATETIME(6)  NOT NULL,
  created_by         BIGINT       NULL,
  updated_at         DATETIME(6)  NOT NULL,
  updated_by         BIGINT       NULL,
  deleted_at         DATETIME(6)  NULL,
  deleted_by         BIGINT       NULL,
  delete_reason      VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product_variant_public_id (public_id),
  UNIQUE KEY uk_product_variant_options (product_id, option1_value_id, option2_value_id, option3_value_id),
  KEY ix_product_variant_deleted_at (deleted_at),
  CONSTRAINT fk_product_variant_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_product_variant_option1 FOREIGN KEY (option1_value_id) REFERENCES product_option_value (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_product_variant_option2 FOREIGN KEY (option2_value_id) REFERENCES product_option_value (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_product_variant_option3 FOREIGN KEY (option3_value_id) REFERENCES product_option_value (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 변형/SKU(PRD 종속·SOFT·public_id var_)';

-- ---------------------------------------------------------------------
-- 25. inventory
-- ---------------------------------------------------------------------
CREATE TABLE inventory (
  id                  BIGINT      NOT NULL AUTO_INCREMENT,
  variant_id          BIGINT      NOT NULL COMMENT 'FK→product_variant·UK(1:1·INV-6)',
  quantity_on_hand    INT         NOT NULL COMMENT '실물 재고(INV-4 ≥0)',
  quantity_reserved   INT         NOT NULL COMMENT '예약 재고(INV-3 ≥0)',
  quantity_available  INT         NOT NULL COMMENT '가용 재고 캐시=on_hand-reserved(INV-1 ≥0·앱 갱신 D-09)',
  created_at          DATETIME(6) NOT NULL,
  created_by          BIGINT      NULL,
  updated_at          DATETIME(6) NOT NULL,
  updated_by          BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_inventory_variant (variant_id),
  KEY ix_inventory_available (quantity_available),
  CONSTRAINT fk_inventory_variant FOREIGN KEY (variant_id) REFERENCES product_variant (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT chk_inventory_on_hand CHECK (quantity_on_hand >= 0),
  CONSTRAINT chk_inventory_reserved CHECK (quantity_reserved >= 0),
  CONSTRAINT chk_inventory_available CHECK (quantity_available >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='재고(INV Aggregate Root·ARCHIVE·variant 1:1)';

-- ---------------------------------------------------------------------
-- 26. inventory_history (append-only)
-- ---------------------------------------------------------------------
CREATE TABLE inventory_history (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  inventory_id    BIGINT       NOT NULL COMMENT 'FK→inventory(N:1)',
  change_type     ENUM('ORDER','CANCEL','RETURN','ADJUST','INBOUND','OUTBOUND') NOT NULL COMMENT '변동 유형·A#8',
  quantity_delta  INT          NOT NULL COMMENT '재고 증감량(부호 포함)',
  reference_type  VARCHAR(50)  NOT NULL COMMENT 'polymorphic 참조 유형·D분류(앱 검증)',
  reference_id    BIGINT       NULL     COMMENT 'polymorphic 참조 id',
  reason          VARCHAR(255) NULL     COMMENT '변동 사유',
  created_at      DATETIME(6)  NOT NULL,
  created_by      BIGINT       NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_inventory_history_inventory FOREIGN KEY (inventory_id) REFERENCES inventory (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='재고 변동 이력(INV 종속·ARCHIVE·append-only)';

-- ---------------------------------------------------------------------
-- 27. cart_item
-- ---------------------------------------------------------------------
CREATE TABLE cart_item (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  user_id     BIGINT      NOT NULL COMMENT 'FK→user(N:1)',
  variant_id  BIGINT      NOT NULL COMMENT 'FK→product_variant(N:1)',
  quantity    INT         NOT NULL COMMENT '수량(CRT-2 ≥1)',
  selected    TINYINT(1)  NOT NULL COMMENT '주문 선택 여부',
  created_at  DATETIME(6) NOT NULL,
  created_by  BIGINT      NULL,
  updated_at  DATETIME(6) NOT NULL,
  updated_by  BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_cart_item_user_variant (user_id, variant_id),
  CONSTRAINT fk_cart_item_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_cart_item_variant FOREIGN KEY (variant_id) REFERENCES product_variant (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT chk_cart_item_quantity CHECK (quantity >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='장바구니(CRT Aggregate Root·HARD)';

-- ---------------------------------------------------------------------
-- 28. order (예약어·백틱)
-- ---------------------------------------------------------------------
CREATE TABLE `order` (
  id               BIGINT      NOT NULL AUTO_INCREMENT,
  public_id        CHAR(30)    NOT NULL COMMENT 'ULID+prefix ord_',
  buyer_id         BIGINT      NOT NULL COMMENT 'FK→user(N:1)',
  order_no         VARCHAR(50) NOT NULL COMMENT '주문번호·UK(ORD-4)·자연키',
  status           ENUM('PENDING_PAYMENT','PAID','PREPARING','SHIPPING','DELIVERED','CONFIRMED','CANCELLED','PARTIAL_CANCEL') NOT NULL COMMENT '주문 상태 8값·B/ORDER_STATUS(ORD-2)',
  total_price      BIGINT      NOT NULL COMMENT '총 주문금액',
  discount_amount  BIGINT      NOT NULL COMMENT '할인액',
  shipping_fee     BIGINT      NOT NULL COMMENT '배송비',
  paid_at          DATETIME(6) NULL     COMMENT '결제 완료 시각',
  ordered_at       DATETIME(6) NULL     COMMENT '주문 확정 시각',
  created_at       DATETIME(6) NOT NULL,
  created_by       BIGINT      NULL,
  updated_at       DATETIME(6) NOT NULL,
  updated_by       BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_public_id (public_id),
  UNIQUE KEY uk_order_order_no (order_no),
  KEY ix_order_buyer_status (buyer_id, status),
  CONSTRAINT fk_order_user FOREIGN KEY (buyer_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문(ORD Aggregate Root·ARCHIVE·public_id ord_)';

-- ---------------------------------------------------------------------
-- 29. order_item
-- ---------------------------------------------------------------------
CREATE TABLE order_item (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  public_id    CHAR(30)    NOT NULL COMMENT 'ULID+prefix oit_',
  order_id     BIGINT      NOT NULL COMMENT 'FK→order(N:1)',
  product_id   BIGINT      NOT NULL COMMENT 'FK→product(N:1)',
  variant_id   BIGINT      NOT NULL COMMENT 'FK→product_variant(N:1)',
  seller_id    BIGINT      NOT NULL COMMENT 'FK→seller·멀티벤더(ORD-3)',
  quantity     INT         NOT NULL COMMENT '수량',
  unit_price   BIGINT      NOT NULL COMMENT '단가(주문 시점 스냅샷)',
  total_price  BIGINT      NOT NULL COMMENT '품목 합계(ORD-5)',
  item_status  ENUM('ORDERED','PAID','PREPARING','SHIPPING','DELIVERED','CONFIRMED','CANCEL_REQUESTED','CANCELLED','RETURN_REQUESTED','RETURNED','EXCHANGE_REQUESTED','EXCHANGED') NOT NULL COMMENT '품목 상태 12값·B/ORDER_ITEM_STATUS',
  created_at   DATETIME(6) NOT NULL,
  created_by   BIGINT      NULL,
  updated_at   DATETIME(6) NOT NULL,
  updated_by   BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_order_item_public_id (public_id),
  KEY ix_order_item_seller_status (seller_id, item_status),
  CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES `order` (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_order_item_variant FOREIGN KEY (variant_id) REFERENCES product_variant (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_order_item_seller FOREIGN KEY (seller_id) REFERENCES seller (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 품목(ORD 종속·ARCHIVE·public_id oit_)';

-- ---------------------------------------------------------------------
-- 30. order_shipping_snapshot (1:1)
-- ---------------------------------------------------------------------
CREATE TABLE order_shipping_snapshot (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  order_id         BIGINT       NOT NULL COMMENT 'FK→order(1:1)',
  recipient_name   VARCHAR(50)  NOT NULL COMMENT '수령인명',
  recipient_phone  VARCHAR(20)  NOT NULL COMMENT '수령인 연락처',
  zonecode         VARCHAR(10)  NOT NULL COMMENT '우편번호(④)',
  address_road     VARCHAR(200) NOT NULL COMMENT '도로명 주소',
  address_jibun    VARCHAR(200) NULL     COMMENT '지번 주소',
  address_detail   VARCHAR(200) NULL     COMMENT '상세 주소',
  delivery_memo    TEXT         NULL     COMMENT '배송 메모',
  created_at       DATETIME(6)  NOT NULL,
  created_by       BIGINT       NULL,
  updated_at       DATETIME(6)  NOT NULL,
  updated_by       BIGINT       NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_order_shipping_snapshot_order FOREIGN KEY (order_id) REFERENCES `order` (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 배송지 스냅샷(ORD 종속·ARCHIVE)';

-- ---------------------------------------------------------------------
-- 31. payment
-- ---------------------------------------------------------------------
CREATE TABLE payment (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  public_id    CHAR(30)     NOT NULL COMMENT 'ULID+prefix pay_',
  order_id     BIGINT       NOT NULL COMMENT 'FK→order(N:1)',
  method       ENUM('CARD','BANK','VBANK','KAKAO') NOT NULL COMMENT '결제수단·A#9',
  amount       BIGINT       NOT NULL COMMENT '결제금액',
  status       ENUM('PENDING','PAID','FAILED','CANCELLED') NOT NULL COMMENT '결제 상태·A#10(PAY-2)',
  pg_provider  VARCHAR(50)  NULL     COMMENT 'PG사',
  pg_tid       VARCHAR(100) NULL     COMMENT 'PG 거래ID·멱등(PAY-3)',
  paid_at      DATETIME(6)  NULL     COMMENT '결제 완료 시각',
  created_at   DATETIME(6)  NOT NULL,
  created_by   BIGINT       NULL,
  updated_at   DATETIME(6)  NOT NULL,
  updated_by   BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payment_public_id (public_id),
  CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES `order` (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='결제(PAY Aggregate Root·ARCHIVE·public_id pay_)';

-- ---------------------------------------------------------------------
-- 32. delivery
-- ---------------------------------------------------------------------
CREATE TABLE delivery (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  public_id      CHAR(30)     NOT NULL COMMENT 'ULID+prefix dlv_',
  order_item_id  BIGINT       NOT NULL COMMENT 'FK→order_item(N:1·DLV-2)',
  carrier        ENUM('CJ','HANJIN','POST','LOGEN') NOT NULL COMMENT '택배사·A#11',
  tracking_no    VARCHAR(100) NULL     COMMENT '운송장번호·UK(DLV-1)·자연키',
  status         ENUM('READY','SHIPPING','DELIVERED') NOT NULL COMMENT '배송 상태·A#12',
  shipped_at     DATETIME(6)  NULL     COMMENT '발송 시각',
  delivered_at   DATETIME(6)  NULL     COMMENT '배송 완료 시각(DLV-3)',
  created_at     DATETIME(6)  NOT NULL,
  created_by     BIGINT       NULL,
  updated_at     DATETIME(6)  NOT NULL,
  updated_by     BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_delivery_public_id (public_id),
  UNIQUE KEY uk_delivery_tracking_no (tracking_no),
  CONSTRAINT fk_delivery_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='배송(DLV Aggregate Root·ARCHIVE·public_id dlv_)';

-- ---------------------------------------------------------------------
-- 33. claim
-- ---------------------------------------------------------------------
CREATE TABLE claim (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  public_id      CHAR(30)    NOT NULL COMMENT 'ULID+prefix clm_',
  order_item_id  BIGINT      NOT NULL COMMENT 'FK→order_item(N:1)',
  type           ENUM('CANCEL','RETURN','EXCHANGE') NOT NULL COMMENT '클레임 유형·A#13',
  reason_code    VARCHAR(50) NOT NULL COMMENT '사유 코드·B/CLAIM_REASON Code 정합(③·ENUM 미적용)',
  reason_detail  TEXT        NULL     COMMENT '상세 사유',
  status         ENUM('REQUESTED','APPROVED','REJECTED','COMPLETED') NOT NULL COMMENT '처리 상태·A#14(CLM-4)',
  requested_by   BIGINT      NULL     COMMENT '요청자 User.id 논리참조(FK 미적용)',
  requested_at   DATETIME(6) NULL     COMMENT '요청 시각',
  processed_at   DATETIME(6) NULL     COMMENT '처리 시각',
  created_at     DATETIME(6) NOT NULL,
  created_by     BIGINT      NULL,
  updated_at     DATETIME(6) NOT NULL,
  updated_by     BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_claim_public_id (public_id),
  KEY ix_claim_status (status),
  CONSTRAINT fk_claim_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='클레임(CLM Aggregate Root·ARCHIVE·public_id clm_)';

-- ---------------------------------------------------------------------
-- 34. refund
-- ---------------------------------------------------------------------
CREATE TABLE refund (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  public_id     CHAR(30)     NOT NULL COMMENT 'ULID+prefix rfn_',
  claim_id      BIGINT       NOT NULL COMMENT 'FK→claim(N:1)',
  payment_id    BIGINT       NOT NULL COMMENT 'FK→payment(N:1)',
  amount        BIGINT       NOT NULL COMMENT '환불금액(PAY-1)',
  status        ENUM('PENDING','COMPLETED','FAILED') NOT NULL COMMENT '환불 상태·A#15',
  refunded_at   DATETIME(6)  NULL     COMMENT '환불 완료 시각',
  pg_refund_id  VARCHAR(100) NULL     COMMENT 'PG 환불ID',
  created_at    DATETIME(6)  NOT NULL,
  created_by    BIGINT       NULL,
  updated_at    DATETIME(6)  NOT NULL,
  updated_by    BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_public_id (public_id),
  CONSTRAINT fk_refund_claim FOREIGN KEY (claim_id) REFERENCES claim (id) ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='환불(CLM 종속·ARCHIVE·public_id rfn_)';

-- ---------------------------------------------------------------------
-- 35. attachment (polymorphic·FK 없음)
-- ---------------------------------------------------------------------
CREATE TABLE attachment (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  public_id       CHAR(30)      NOT NULL COMMENT 'ULID+prefix att_(ATT-2)',
  target_type     VARCHAR(50)   NOT NULL COMMENT 'polymorphic 대상 유형·D분류(ATT-1·앱 검증)',
  target_id       BIGINT        NOT NULL COMMENT 'polymorphic 대상 id 논리참조',
  file_name       VARCHAR(200)  NOT NULL COMMENT '원본 파일명',
  file_path       VARCHAR(2048) NOT NULL COMMENT '저장 경로/URL',
  mime_type       VARCHAR(100)  NULL     COMMENT 'MIME 타입',
  file_size       BIGINT        NULL     COMMENT '파일 크기(byte)',
  display_order   INT           NOT NULL COMMENT '정렬 순서',
  created_at      DATETIME(6)   NOT NULL,
  created_by      BIGINT        NULL,
  updated_at      DATETIME(6)   NOT NULL,
  updated_by      BIGINT        NULL,
  deleted_at      DATETIME(6)   NULL,
  deleted_by      BIGINT        NULL,
  delete_reason   VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_attachment_public_id (public_id),
  KEY ix_attachment_target (target_type, target_id),
  KEY ix_attachment_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='첨부파일(ATT Aggregate Root·SOFT·public_id att_)';

-- ---------------------------------------------------------------------
-- 36. audit_log (polymorphic·FK 없음·append-only)
-- ---------------------------------------------------------------------
CREATE TABLE audit_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  public_id     CHAR(30)     NOT NULL COMMENT 'ULID+prefix aud_(AUD-4)',
  actor_user_id BIGINT       NULL     COMMENT '행위자 User.id 논리참조(FK 미적용)',
  actor_role    VARCHAR(50)  NULL     COMMENT '행위자 역할',
  action        ENUM('CREATE','UPDATE','DELETE','APPROVE','REJECT','LOGIN','LOGOUT') NOT NULL COMMENT '행위·A#18',
  target_type   VARCHAR(50)  NOT NULL COMMENT 'polymorphic 대상 유형·D분류',
  target_id     BIGINT       NOT NULL COMMENT 'polymorphic 대상 id 논리참조',
  diff_json     LONGTEXT     NULL     COMMENT '변경 필드 JSON·민감정보 마스킹(AUD-3·D-11)',
  ip_address    VARCHAR(45)  NULL     COMMENT '요청 IP(IPv6 대응)',
  user_agent    VARCHAR(500) NULL     COMMENT 'User-Agent',
  created_at    DATETIME(6)  NOT NULL,
  created_by    BIGINT       NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_audit_log_public_id (public_id),
  KEY ix_audit_log_target (target_type, target_id, created_at),
  KEY ix_audit_log_actor (actor_user_id, created_at),
  CONSTRAINT chk_audit_log_diff_json CHECK (diff_json IS NULL OR JSON_VALID(diff_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='감사 로그(AUD Aggregate Root·ARCHIVE·append-only·public_id aud_)';

-- ---------------------------------------------------------------------
-- 37. notification_log (polymorphic·FK 없음·append-only)
-- ---------------------------------------------------------------------
CREATE TABLE notification_log (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  recipient_user_id  BIGINT       NULL     COMMENT '수신자 User.id 논리참조(FK 미적용)',
  channel            ENUM('EMAIL','SMS','PUSH','IN_APP') NOT NULL COMMENT '발송 채널·A#16(NOT-2)',
  template_code      VARCHAR(100) NOT NULL COMMENT '템플릿 코드',
  target_type        VARCHAR(50)  NOT NULL COMMENT 'polymorphic 대상 유형·D분류(NOT-3)',
  target_id          BIGINT       NOT NULL COMMENT 'polymorphic 대상 id 논리참조',
  title              VARCHAR(200) NULL     COMMENT '알림 제목',
  content            TEXT         NULL     COMMENT '알림 본문',
  status             ENUM('PENDING','SENT','FAILED') NOT NULL COMMENT '발송 상태·A#17',
  sent_at            DATETIME(6)  NULL     COMMENT '발송 시각',
  failed_reason      TEXT         NULL     COMMENT '실패 사유',
  created_at         DATETIME(6)  NOT NULL,
  created_by         BIGINT       NULL,
  PRIMARY KEY (id),
  KEY ix_notification_log_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='알림 로그(Infra/Event·ARCHIVE·append-only)';
