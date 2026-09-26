-- V39: delivery.tracking_no 전역 유니크 제거 → 일반 인덱스 (D-227 — D-184 §8 대체)
-- 작성일: 2026-09-26
-- 참조: 배송 행은 주문 품목 단위라 합포장(같은 상자·같은 송장)이 여러 행에 같은 번호를 쓴다. 택배사는 번호를 재사용하고
--       택배사끼리 번호가 겹칠 수 있다. 전역 유니크는 정상 입력을 막고, 위반은 처리되지 않은 500으로 샜다(운영 2026-09-25).
--       송장번호 검색(관리자·셀러 배송 목록 keyword 동등 비교)은 같은 컬럼의 일반 인덱스로 유지한다.
--
-- 데이터 변경 없음. 기존 송장값(형식 규칙 이전 입력 포함)은 그대로 둔다.
ALTER TABLE delivery
  DROP KEY uk_delivery_tracking_no,
  ADD KEY ix_delivery_tracking_no (tracking_no);

-- rollback:
--   ALTER TABLE delivery DROP KEY ix_delivery_tracking_no, ADD UNIQUE KEY uk_delivery_tracking_no (tracking_no);
--   V39 적용 뒤 같은 송장번호 행이 2건 이상 생겼으면 UNIQUE 재생성이 실패한다 — 중복 행을 먼저 확인·처리한 뒤 실행한다.
--   확인: SELECT tracking_no, COUNT(*) FROM delivery WHERE tracking_no IS NOT NULL GROUP BY tracking_no HAVING COUNT(*) > 1;
--   수동 롤백 뒤 DELETE FROM flyway_schema_history WHERE version = '39'; 로 이력도 되돌린다(남기면 스키마와 이력이 어긋난다).
