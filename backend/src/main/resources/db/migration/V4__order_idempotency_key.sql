-- =====================================================================
-- V4__order_idempotency_key.sql — 주문 생성 멱등성 키 저장 테이블 신설
-- 트랙        : Track 4 (Order API·feat/track-4-order-api)
-- 입력(SoT)   : decisions.md D-44·D-44a·D-44b·D-52 / docs/track-4/expected-spec.md §8·§9·§10
-- 대상 DBMS   : MariaDB 11.4 / 스키마 zslab_mall
-- V1 기준 변경 : 신규 테이블 order_idempotency_key 1종 추가(기존 테이블 무변경)
--   (1) PK (buyer_id, idempotency_key) 복합 — 멱등 스코프 (buyer_id, idempotency_key) 유일(§8)
--   (2) status ENUM('IN_PROGRESS','COMPLETED') — 내부 기술 상태(§12·UI 비노출·varchar 무제약 금지)
--   (3) order_id NULL — INSERT 시 NULL·createOrder COMMIT 후 UPDATE(D-52 2·3단계·재시도 복구 분기 키)
--   (4) response_body LONGTEXT NULL — 2xx 성공 응답 직렬화 캐시(§10·D-44b·4xx/5xx 미저장)
-- 규약        : V1 계승 (charset utf8mb4_unicode_ci·시간 DATETIME(6) UTC)
-- D-25 정합   : V1 본문 직접 수정 금지·신규 V4 마이그레이션으로만 변경. 데이터 손실 없는 신규 테이블 추가.
-- 보존 윈도우 : 0~24h 재요청 SLA·24~72h 디버깅·72h 이후 배치 일괄 삭제(§9·CR-21 기각).
--               삭제 배치·created_at 인덱스는 본 트랙 범위 밖(운영 트래픽 발생 시점 도입·§9 향후 재평가).
-- 참조 정책   : buyer_id·order_id는 논리 참조(FK 미부여) — 멱등성은 인프라 성격 테이블이며
--               §9 스키마에 FK 미명시. Redis 매체 마이그레이션(Track 7) 시 이관 부담 최소화.
-- rollback    : 아래 보상 SQL(주석) 참조. 신규 테이블이므로 DROP으로 무손실 원복(데이터 0건 전제).
--
-- [rollback 보상 SQL]
--   DROP TABLE IF EXISTS order_idempotency_key;
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

CREATE TABLE order_idempotency_key (
  buyer_id         BIGINT       NOT NULL COMMENT 'FK→user(논리 참조)·멱등 스코프 1요소(§8)',
  idempotency_key  VARCHAR(128) NOT NULL COMMENT '클라이언트 전달 키(ULID·UUID v4·최대 128자·D-44)',
  order_id         BIGINT       NULL     COMMENT '생성 주문 id(논리 참조)·INSERT 시 NULL·createOrder COMMIT 후 UPDATE(D-52)',
  status           ENUM('IN_PROGRESS','COMPLETED') NOT NULL COMMENT '멱등 처리 상태·내부 기술 상태(§12·UI 비노출)',
  response_body    LONGTEXT     NULL     COMMENT '2xx 성공 응답 직렬화 캐시(§10·D-44b·4xx/5xx 미저장)',
  created_at       DATETIME(6)  NOT NULL COMMENT 'INSERT 시각·보존 윈도우(0/24/72h) 기준(§9)',
  completed_at     DATETIME(6)  NULL     COMMENT 'status=COMPLETED 전이 시각(§10·D-52 5단계)',
  PRIMARY KEY (buyer_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='주문 생성 멱등성 키(Track 4·D-44a·내부 기술 테이블)';
