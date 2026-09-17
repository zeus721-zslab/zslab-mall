-- V24: 반품 흐름 골격 — 배송 방향·클레임 검수 컬럼 (Track 81-A·D-170)
-- 작성일: 2026-09-17
-- 참조: decisions.md D-170(회수 Delivery = delivery.direction RETURN + claim_id / 검수 = claim milestone 컬럼·상태 4값 유지)

-- 배송 방향. 기존 행은 전부 발송(OUTBOUND)이라 DEFAULT로 백필한다. 반품 회수 Delivery는 RETURN이며 claim_id로 클레임에 연결된다.
-- 값 집합은 ENUM으로 잠근다(4층위 ① DB·Java enum com.zslab.mall.delivery.enums.DeliveryDirection 1:1).
ALTER TABLE delivery
  ADD COLUMN direction ENUM('OUTBOUND','RETURN') NOT NULL DEFAULT 'OUTBOUND'
      COMMENT '배송 방향·OUTBOUND=구매자 발송·RETURN=반품 회수(D-170)' AFTER order_item_id;

-- 검수(반품 회수 후 합격/불합격)는 ClaimStatus 값 집합을 늘리지 않고 milestone 컬럼으로 둔다(pickedUpAt 패턴).
-- inspection_result는 CHECK로 잠근다(4층위 ①·Java enum ClaimInspectionResult). restock은 PASS 시 재입고 여부(불량품 폐기 = 0).
ALTER TABLE claim
  ADD COLUMN inspected_at DATETIME(6) NULL COMMENT '검수 시각(D-170)' AFTER picked_up_at,
  ADD COLUMN inspection_result VARCHAR(20) NULL COMMENT '검수 결과·ClaimInspectionResult PASS|FAIL(D-170)' AFTER inspected_at,
  ADD COLUMN restock TINYINT(1) NULL COMMENT '검수 PASS 시 재입고 여부·FAIL/미검수 NULL(D-170)' AFTER inspection_result,
  ADD CONSTRAINT chk_claim_inspection_result
    CHECK (inspection_result IS NULL OR inspection_result IN ('PASS', 'FAIL'));

-- 거부 사유 코드에 검수 불합격(INSPECTION_FAILED·RETURN 검수 FAIL 경로 전용)을 추가한다 — V23 CHECK를 교체(4층위 ① DB·Java enum 1:1).
ALTER TABLE claim DROP CONSTRAINT chk_claim_reject_reason_code;
ALTER TABLE claim
  ADD CONSTRAINT chk_claim_reject_reason_code
    CHECK (reject_reason_code IS NULL
           OR reject_reason_code IN ('ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER', 'INSPECTION_FAILED'));

-- 배송완료 시각의 SoT는 delivery.delivered_at(order_item 컬럼 신설 기각·D-170). 반품 기한·자동 구매확정의 기준은 "원 주문 발송"
-- (direction=OUTBOUND·claim_id IS NULL·DELIVERED)의 delivered_at이며 교환품 발송·검수 불합격 재발송(claim_id NOT NULL)은 제외한다.
-- 반품 기한(7일)은 품목 FK 인덱스로 충분하나 81-B 자동 구매확정 배치가 "원 발송이 DELIVERED이고 delivered_at ≤ now−7d"를 풀스캔 없이
-- 잡아야 하므로 delivery 쪽에만 복합 인덱스를 둔다(등치 direction·status·claim_id IS NULL 뒤에 범위 delivered_at).
CREATE INDEX ix_delivery_direction_status_delivered_at ON delivery (direction, status, claim_id, delivered_at);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- DROP INDEX ix_delivery_direction_status_delivered_at ON delivery;
-- ALTER TABLE claim DROP CONSTRAINT chk_claim_reject_reason_code;
-- ALTER TABLE claim ADD CONSTRAINT chk_claim_reject_reason_code
--   CHECK (reject_reason_code IS NULL OR reject_reason_code IN ('ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'));
-- ALTER TABLE claim DROP CONSTRAINT chk_claim_inspection_result;
-- ALTER TABLE claim DROP COLUMN restock;
-- ALTER TABLE claim DROP COLUMN inspection_result;
-- ALTER TABLE claim DROP COLUMN inspected_at;
-- ALTER TABLE delivery DROP COLUMN direction;
-- ============================================================
