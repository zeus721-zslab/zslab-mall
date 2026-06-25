-- =====================================================================
-- V3__payment_track3.sql — Payment Track 3 컬럼·제약 보강
-- 트랙        : Track 3 (Payment Mock·feat/track-3-payment)
-- 입력(SoT)   : decisions.md D-31·D-32·D-35 / db-schema-decisions.md §2.5(v2.5)
--               invariants.md §2.11(PAY-3a·PAY-3b) / state-machine.md §1
-- 대상 DBMS   : MariaDB 11.4 / 스키마 zslab_mall
-- V1 기준 변경 : payment 테이블에 컬럼 3종·제약 3건 추가
--   (1) failure_code        VARCHAR(50) NULL  — 결제 실패 사유 코드(D-30·D-32)
--   (2) payment_attempt_key CHAR(30) NOT NULL — 결제 시도 식별자·콜백 매핑 1차 키(D-35·prefix pat_)
--   (3) expires_at          DATETIME(6) NULL  — PENDING 결제 만료 시각·기본 +30분(D-32)
--   (4) UNIQUE uk_payment_attempt_key (payment_attempt_key)        — D-35
--   (5) UNIQUE uk_payment_provider_pg_tid (pg_provider, pg_tid)    — PAY-3b·D-31
--   (6) CHECK chk_payment_pg_tid_provider (pg_tid IS NULL OR pg_provider IS NOT NULL) — D-31
-- 규약        : V1 계승 (charset utf8mb4_unicode_ci·시간 DATETIME(6) UTC)
-- D-25 정합   : V1 본문 직접 수정 금지·신규 V3 마이그레이션으로만 변경(§3). 데이터 손실 없는 컬럼·제약 추가(§4).
-- rollback    : 아래 보상 SQL(주석) 참조. payment 데이터 존재 시 payment_attempt_key NOT NULL
--               백필 선행 필요(신규 환경·데이터 0건이면 불요).
--
-- [사전 검증 쿼리] — 마이그레이션 실행 전 별도 스텝(CI 또는 수동)에서 0건 확인
--   1. (pg_provider, pg_tid) 중복 검증(PAY-3b UNIQUE 충돌 예방):
--      SELECT pg_provider, pg_tid, COUNT(*) FROM payment
--       WHERE pg_tid IS NOT NULL GROUP BY pg_provider, pg_tid HAVING COUNT(*) > 1;
--   2. pg_provider NULL 이면서 PAID 인 행 검증(CHECK 위반 예방·논리 점검):
--      SELECT COUNT(*) FROM payment WHERE status='PAID' AND pg_provider IS NULL;
--   3. 기존 payment 행 존재 시 payment_attempt_key 백필 필요(현재는 신규 환경·데이터 0건):
--      SELECT COUNT(*) FROM payment;
--
-- [rollback 보상 SQL]
--   ALTER TABLE payment
--     DROP CHECK chk_payment_pg_tid_provider,
--     DROP INDEX uk_payment_provider_pg_tid,
--     DROP INDEX uk_payment_attempt_key,
--     DROP COLUMN expires_at,
--     DROP COLUMN payment_attempt_key,
--     DROP COLUMN failure_code;
-- =====================================================================

SET NAMES utf8mb4;
SET time_zone = '+00:00';

ALTER TABLE payment
  ADD COLUMN failure_code VARCHAR(50) NULL
    COMMENT '결제 실패 사유 코드(PaymentFailed 이벤트·운영 화면 표시·D-30·D-32)',
  ADD COLUMN payment_attempt_key CHAR(30) NOT NULL
    COMMENT '결제 시도 식별자·콜백 매핑 1차 키(D-35·prefix pat_)',
  ADD COLUMN expires_at DATETIME(6) NULL
    COMMENT 'PENDING 결제 만료 시각·기본 +30분(D-32)',
  ADD UNIQUE KEY uk_payment_attempt_key (payment_attempt_key),
  ADD UNIQUE KEY uk_payment_provider_pg_tid (pg_provider, pg_tid),
  ADD CONSTRAINT chk_payment_pg_tid_provider
    CHECK (pg_tid IS NULL OR pg_provider IS NOT NULL);
