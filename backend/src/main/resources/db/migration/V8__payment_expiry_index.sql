-- =====================================================================
-- V8__payment_expiry_index.sql — payment 만료 배치 조회 인덱스
-- 트랙        : Track 25 (자동 예약 만료·feat/track-25-payment-auto-expire·D-08 M-14)
-- 입력(SoT)   : decisions.md D-32(expires_at 도입)·D-08 M-14 / db-schema-decisions.md Payment 절
-- 대상 DBMS   : MariaDB 11.4 / 스키마 zslab_mall
-- 목적        : ExpirePaymentScheduler 만료 후보 조회
--               (WHERE status='PENDING' AND expires_at < now ORDER BY expires_at)를
--               full table scan 없이 커버. status 선행 등가 + expires_at 범위/정렬을
--               (status, expires_at) 복합 인덱스가 처리한다.
-- V3 기준 변경 : V3에서 추가한 payment.expires_at에 대한 조회 인덱스 1건 신설(데이터 변경 없음).
-- 규약        : V1/V3 계승(charset utf8mb4_unicode_ci·시간 DATETIME(6) UTC).
-- D-25 정합   : V1/V3 본문 직접 수정 금지·신규 Vn 마이그레이션으로만 변경(§3). 인덱스 추가는 데이터 손실 없음(§4).
-- rollback    : 아래 보상 SQL(주석) 참조.
--
-- [rollback 보상 SQL]
--   DROP INDEX idx_payment_expire ON payment;
-- =====================================================================

CREATE INDEX idx_payment_expire ON payment (status, expires_at);
