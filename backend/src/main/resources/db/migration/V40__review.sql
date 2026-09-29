-- V40: 상품 리뷰 — review · review_keyword · review_keyword_selection · review_helpful · product_review_summary (Track 106-1 PR1)
-- 작성일: 2026-09-29
-- 참조: docs/track-106-1/recon-report.md §9(결정 1~10)·§11(공통 컬럼)·§12(구현 정찰)
-- 목적: 구매확정 품목당 리뷰 1개(별점·키워드·본문·사진), 공개 조회·관리자 숨김·도움됐어요·상품별 한 줄 요약을 저장한다.
--
-- 설계:
--   review            = 구매확정(CONFIRMED) 품목당 1개(uk_review_order_item 단순 UNIQUE — 삭제는 soft delete이고 재작성 불가라
--                       deleted_at 파생키가 필요 없다. 동시 작성은 UNIQUE 위반 → 409). option_label은 작성 시점 order_item 스냅샷 복사.
--                       status = VARCHAR + CHECK(VISIBLE·HIDDEN) — 4층위 enum 잠금 (1)DB · Java ReviewStatus · 관리자 DTO @Pattern.
--                       hidden_reason = 관리자 숨김 사유(작성자 단건 조회에 노출). HIDDEN일 때만 값이 있고 숨김 해제 시 비운다 —
--                       CHECK로 상태와 쌍을 강제한다(HIDDEN이면 공백만인 사유도 거부). 감사 이력(diff_json)에도 남지만 감사는 추적용이라 화면 원천으로 쓰지 않는다.
--                       helpful_count = review_helpful 행 수(원자적 UPDATE로 증감·CHECK ≥ 0).
--                       buyer_id = 작성자 논리참조(FK 미적용·행위자 컬럼 관례). created_by는 AuditorAware 미구현으로 항상 NULL.
--   review_keyword    = 키워드 마스터. top_category_id NULL = 기본 세트(모든 상품), 값 = 해당 최상위 카테고리 전용 세트(PR2 demo-seed).
--                       code는 요청·응답 식별자라 전역 유일(범위가 달라도 같은 code를 쓰지 않는다 — 요청이 code만 보내도 1행으로 해석된다).
--                       group_code = VARCHAR + CHECK — Java ReviewKeywordGroup과 1:1.
--   review_keyword_selection = 리뷰가 고른 키워드(PK (review_id, keyword_id)).
--   review_helpful    = 도움됐어요 1인 1회(PK (review_id, user_id)). user_id는 논리참조.
--   product_review_summary = 상품별 한 줄 요약(커밋 후 비동기 재계산·upsert). 공개 리뷰 0건이면 행을 지운다(= "요약 없음").
--   FK = ON DELETE RESTRICT ON UPDATE RESTRICT(최근 관례·V29). 대상 order_item·product·category는 운영 경로에서 하드 삭제되지 않는다
--        (order_item 하드 삭제는 미결제 만료 주문만 — 구매확정 불가라 리뷰가 없다).
-- 데이터 안전: 신규 테이블 5개 + 기본 키워드 6행 INSERT — 기존 행 변경 없음.

CREATE TABLE review (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  public_id       CHAR(30)      NOT NULL COMMENT 'ULID+prefix rvw_',
  order_item_id   BIGINT        NOT NULL COMMENT 'FK→order_item(1:1·품목당 1개)',
  product_id      BIGINT        NOT NULL COMMENT 'FK→product(N:1·상품별 목록·집계 기준)',
  buyer_id        BIGINT        NOT NULL COMMENT '작성 구매자 User.id 논리참조(FK 미적용)',
  rating          INT           NOT NULL COMMENT '별점 1~5',
  content         VARCHAR(1000) NOT NULL COMMENT '본문',
  option_label    VARCHAR(500)  NULL     COMMENT '옵션 라벨 스냅샷(작성 시점 order_item.option_label·단순상품 NULL)',
  status          VARCHAR(20)   NOT NULL COMMENT '공개 상태(VISIBLE·HIDDEN)',
  hidden_reason   VARCHAR(200)  NULL     COMMENT '숨김 사유(관리자 입력·HIDDEN일 때만)',
  helpful_count   INT           NOT NULL DEFAULT 0 COMMENT '도움됐어요 수(review_helpful 행 수)',
  created_at      DATETIME(6)   NOT NULL,
  created_by      BIGINT        NULL,
  updated_at      DATETIME(6)   NOT NULL,
  updated_by      BIGINT        NULL,
  deleted_at      DATETIME(6)   NULL,
  deleted_by      BIGINT        NULL,
  delete_reason   VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_public_id (public_id),
  UNIQUE KEY uk_review_order_item (order_item_id),
  -- 공개 목록·집계는 항상 product_id = ? AND status = 'VISIBLE' AND deleted_at IS NULL로 좁힌 뒤 최신순·도움순 정렬한다.
  -- product_id 단일(FK 자동 인덱스)로는 숨김·삭제 행까지 읽으므로 세 조건을 한 인덱스로 묶고 최신순 정렬 열을 뒤에 둔다.
  KEY ix_review_product_list (product_id, status, deleted_at, created_at),
  KEY ix_review_deleted_at (deleted_at),
  CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5),
  CONSTRAINT chk_review_status CHECK (status IN ('VISIBLE','HIDDEN')),
  CONSTRAINT chk_review_hidden_reason CHECK (
    (status = 'HIDDEN' AND hidden_reason IS NOT NULL AND CHAR_LENGTH(TRIM(hidden_reason)) > 0)
    OR (status = 'VISIBLE' AND hidden_reason IS NULL)),
  CONSTRAINT chk_review_helpful_count CHECK (helpful_count >= 0),
  CONSTRAINT fk_review_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES product (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품 리뷰(RVW Aggregate Root·SOFT·public_id rvw_·Track 106-1)';

CREATE TABLE review_keyword (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  code             VARCHAR(50)  NOT NULL COMMENT '키워드 코드(요청·응답 식별자·전역 유일)',
  label            VARCHAR(50)  NOT NULL COMMENT '표시 문구',
  group_code       VARCHAR(20)  NOT NULL COMMENT '묶음(DELIVERY·PACKAGING·QUALITY·VALUE·PRODUCT)',
  top_category_id  BIGINT       NULL     COMMENT 'FK→category(최상위)·NULL = 기본 세트(모든 상품)',
  display_order    INT          NOT NULL COMMENT '표시 순서',
  created_at       DATETIME(6)  NOT NULL,
  updated_at       DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_keyword_code (code),
  CONSTRAINT chk_review_keyword_group CHECK (group_code IN ('DELIVERY','PACKAGING','QUALITY','VALUE','PRODUCT')),
  CONSTRAINT fk_review_keyword_category FOREIGN KEY (top_category_id) REFERENCES category (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='리뷰 키워드 마스터(기본 세트·카테고리별 세트·Track 106-1)';

CREATE TABLE review_keyword_selection (
  review_id   BIGINT  NOT NULL COMMENT 'FK→review',
  keyword_id  BIGINT  NOT NULL COMMENT 'FK→review_keyword',
  PRIMARY KEY (review_id, keyword_id),
  CONSTRAINT fk_review_keyword_selection_review FOREIGN KEY (review_id) REFERENCES review (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_review_keyword_selection_keyword FOREIGN KEY (keyword_id) REFERENCES review_keyword (id)
      ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='리뷰별 선택 키워드(RVW 종속·Track 106-1)';

CREATE TABLE review_helpful (
  review_id   BIGINT       NOT NULL COMMENT 'FK→review',
  user_id     BIGINT       NOT NULL COMMENT '누른 구매자 User.id 논리참조(FK 미적용)',
  created_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (review_id, user_id),
  CONSTRAINT fk_review_helpful_review FOREIGN KEY (review_id) REFERENCES review (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='리뷰 도움됐어요(1인 1회·RVW 종속·Track 106-1)';

CREATE TABLE product_review_summary (
  product_id    BIGINT        NOT NULL COMMENT 'FK→product(1:1)',
  summary_text  VARCHAR(500)  NOT NULL COMMENT '한 줄 요약(LlmPort 생성)',
  review_count  INT           NOT NULL COMMENT '요약 계산 시점 공개 리뷰 수',
  updated_at    DATETIME(6)   NOT NULL,
  PRIMARY KEY (product_id),
  CONSTRAINT fk_product_review_summary_product FOREIGN KEY (product_id) REFERENCES product (id)
      ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='상품별 리뷰 한 줄 요약(재계산 가능 파생·Track 106-1)';

-- 기본 키워드 세트(카테고리 무관·모든 상품). 카테고리별 세트는 운영 데이터가 아니라 데모 시드(PR2)가 넣는다.
INSERT INTO review_keyword (code, label, group_code, top_category_id, display_order, created_at, updated_at) VALUES
  ('DELIVERY_FAST',       '배송이 빨라요',     'DELIVERY',  NULL, 1, NOW(6), NOW(6)),
  ('PACKAGING_NEAT',      '포장이 꼼꼼해요',   'PACKAGING', NULL, 2, NOW(6), NOW(6)),
  ('QUALITY_GOOD',        '품질이 좋아요',     'QUALITY',   NULL, 3, NOW(6), NOW(6)),
  ('SAME_AS_DESCRIPTION', '설명과 같아요',     'QUALITY',   NULL, 4, NOW(6), NOW(6)),
  ('VALUE_FOR_MONEY',     '가격 대비 좋아요',  'VALUE',     NULL, 5, NOW(6), NOW(6)),
  ('WILL_REPURCHASE',     '재구매할래요',      'VALUE',     NULL, 6, NOW(6), NOW(6));

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 리뷰 첨부(attachment.target_type = 'REVIEW') 행·파일은 테이블과 무관하게 남으므로 필요 시 별도로 정리한다.
--
-- DROP TABLE product_review_summary;
-- DROP TABLE review_helpful;
-- DROP TABLE review_keyword_selection;
-- DROP TABLE review_keyword;
-- DROP TABLE review;
-- ============================================================
