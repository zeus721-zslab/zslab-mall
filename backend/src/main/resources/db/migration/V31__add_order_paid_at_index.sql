-- V31: order.paid_at 인덱스 (Track 86·D-180 관리자 대시보드)
-- 작성일: 2026-09-18
-- 참조: docs/track-86/recon-report.md §4 — 대시보드 실시간 집계(오늘·이번 달·6개월 월별·30일 일별·최근 결제 주문·상위 셀러/상품)
--       8종 중 6종이 paid_at 범위 조건인데 `order`에 paid_at 인덱스가 없어 매 요청 전 행 스캔(현재 규모에서는 ms 단위·선제 추가).
--
-- 추가형(컬럼·NOT NULL·데이터 변경 없음). paid_at NULL(미결제·만료) 행은 인덱스 범위 조건에서 자연 제외된다.
ALTER TABLE `order`
  ADD KEY ix_order_paid_at (paid_at);

-- rollback:
--   ALTER TABLE `order` DROP KEY ix_order_paid_at;
