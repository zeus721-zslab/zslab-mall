-- V38: order (buyer_id, ordered_at) 복합 인덱스 (Track 105-4b·D-224 — D-223 §8 이월 해소)
-- 작성일: 2026-09-25
-- 참조: 구매자 주문 현황 요약(o.buyerId = ? AND o.orderedAt >= ?)과 주문 목록 품목 상태 필터(같은 조건 + ordered_at DESC 페이지)가
--       기존 ix_order_buyer_status(buyer_id, status)의 buyer_id 접두로 찾은 뒤 ordered_at을 행 필터·정렬하던 것을 인덱스 범위로 바꾼다.
--
-- 추가형(컬럼·NOT NULL·데이터 변경 없음). 기존 인덱스·FK(fk_order_user)는 그대로 둔다.
ALTER TABLE `order`
  ADD KEY ix_order_buyer_ordered_at (buyer_id, ordered_at);

-- rollback:
--   ALTER TABLE `order` DROP KEY ix_order_buyer_ordered_at;
