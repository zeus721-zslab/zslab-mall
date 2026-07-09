-- =====================================================================
-- V18__order_status_payment_expired.sql — order.status ENUM에 PAYMENT_EXPIRED 추가
-- 트랙        : FE-12c (D-153 레벨3·미결제 주문 종료 모델 재설계)
-- 입력(SoT)   : docs/frontend/recon-report-fe-12c.md Q2·Q4 / 채팅 결정(미결제 종료를 CANCELLED에서 분리)
-- 대상 DBMS   : MariaDB 11.4 / 스키마 zslab_mall
-- 목적        : 결제 미완(결제창 이탈·PG오류·30분 방치·시작 실패) 종료 주문을 CANCELLED와 구분하는
--               신규 종료 상태 PAYMENT_EXPIRED를 order.status ENUM에 추가한다(구매자 목록 비노출·삭제 대상).
--               기존 CANCELLED는 결제 후 취소(Claim CANCEL) 전용으로 의미가 정리된다.
-- V1 기준 변경 : order.status ENUM 값 1개 추가(기존 8값 무변경·데이터 영향 없음·ENUM 끝에 추가).
-- 규약        : V1 계승(charset utf8mb4_unicode_ci·시간 DATETIME(6) UTC).
-- D-25 정합   : V1 본문 직접 수정 금지·신규 Vn 마이그레이션으로만 변경. ENUM 값 추가는 기존 행 무영향(무손실).
-- rollback    : 아래 보상 SQL(주석) 참조. PAYMENT_EXPIRED 행이 없을 때만 안전(값 제거 전 데이터 확인 필요).
--
-- [rollback 보상 SQL]
--   ALTER TABLE `order` MODIFY COLUMN status
--     ENUM('PENDING_PAYMENT','PAID','PREPARING','SHIPPING','DELIVERED','CONFIRMED','CANCELLED','PARTIAL_CANCEL')
--     NOT NULL COMMENT '주문 상태 8값·B/ORDER_STATUS(ORD-2)';
-- =====================================================================

ALTER TABLE `order` MODIFY COLUMN status
  ENUM('PENDING_PAYMENT','PAID','PREPARING','SHIPPING','DELIVERED','CONFIRMED','CANCELLED','PARTIAL_CANCEL','PAYMENT_EXPIRED')
  NOT NULL COMMENT '주문 상태 9값·B/ORDER_STATUS(ORD-2)·PAYMENT_EXPIRED=미결제 종료(FE-12c)';
