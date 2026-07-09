-- =====================================================================
-- V19__payment_status_expired.sql — payment.status ENUM에 EXPIRED 추가
-- 트랙        : FE-12c (D-153 레벨3·미결제 주문 종료 모델 재설계)
-- 입력(SoT)   : docs/frontend/recon-report-fe-12c.md 2차 Q3·Q4 / 채팅 결정(Payment 종료를 FAILED 재사용 아닌 별도 상태로)
-- 대상 DBMS   : MariaDB 11.4 / 스키마 zslab_mall
-- 목적        : 결제 미완 종료(결제창 이탈·PG오류·30분 만료·시작 실패)를 표현하는 신규 종료 상태 EXPIRED를
--               payment.status ENUM에 추가한다. 기존 FAILED(재시도 가능한 PG 실패 의미)와 구분하며,
--               failure_code 문자열 구분(PG_FAILURE·CANCELLED_BEFORE_PAYMENT·PAYMENT_EXPIRED)을 상태로 승격한다.
--               전이 PENDING→EXPIRED는 종결(불가역). PENDING→PAID·FAILED와 병존.
-- V1 기준 변경 : payment.status ENUM 값 1개 추가(기존 4값 무변경·데이터 영향 없음·ENUM 끝에 추가).
-- 규약        : V1 계승(charset utf8mb4_unicode_ci·시간 DATETIME(6) UTC).
-- D-25 정합   : V1 본문 직접 수정 금지·신규 Vn 마이그레이션으로만 변경. ENUM 값 추가는 기존 행 무영향(무손실).
-- rollback    : 아래 보상 SQL(주석) 참조. EXPIRED 행이 없을 때만 안전(값 제거 전 데이터 확인 필요).
--
-- [rollback 보상 SQL]
--   ALTER TABLE payment MODIFY COLUMN status
--     ENUM('PENDING','PAID','FAILED','CANCELLED')
--     NOT NULL COMMENT '결제 상태·A#10(PAY-2)';
-- =====================================================================

ALTER TABLE payment MODIFY COLUMN status
  ENUM('PENDING','PAID','FAILED','CANCELLED','EXPIRED')
  NOT NULL COMMENT '결제 상태 5값·A#10(PAY-2)·EXPIRED=미결제 종료(FE-12c)';
