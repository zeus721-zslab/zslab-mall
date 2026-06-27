-- V5: refund 테이블 제약·인덱스 보강 (Track 5 외부 검토 흡수)
-- 참조: decisions.md D-76(pg_refund_id 멱등성)·recon-report.md CR-13·CR-14·CR-15

-- CR-13: payment_id + status 복합 인덱스 (정산·PAY-1 누적 조회 최적화)
CREATE INDEX idx_refund_payment_status ON refund(payment_id, status);

-- CR-14: refunded_at 상태기계 정합성 CHECK
--   PENDING 상태에서 refunded_at은 NULL이어야 한다.
--   COMPLETED 상태에서는 NOT NULL이어야 한다 (D-70: COMPLETED 전이 시스템 시각).
--   FAILED 상태는 refunded_at 제약 없음 (환불 미완료·markFailed()는 refunded_at 미설정).
ALTER TABLE refund ADD CONSTRAINT chk_refund_completed_at CHECK (
    (status = 'PENDING' AND refunded_at IS NULL)
    OR (status = 'COMPLETED' AND refunded_at IS NOT NULL)
    OR (status = 'FAILED')
);

-- CR-15: pg_refund_id webhook 멱등성 UNIQUE (MariaDB UNIQUE는 NULL 다건 허용)
--   initiate 실패 시 NULL 허용·정상 initiate 후 PG 응답값 중복 방어
ALTER TABLE refund ADD CONSTRAINT uk_refund_pg_refund_id UNIQUE (pg_refund_id);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- ALTER TABLE refund DROP CONSTRAINT uk_refund_pg_refund_id;
-- ALTER TABLE refund DROP CONSTRAINT chk_refund_completed_at;
-- DROP INDEX idx_refund_payment_status ON refund;
--
-- MariaDB 버전별 문법 주의: DROP CONSTRAINT는 10.2.1+
-- ============================================================
