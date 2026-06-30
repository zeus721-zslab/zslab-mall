-- V7: Track 14 PR-2 EXCHANGE
-- D-98 Q13·외부 검토 2차 R5 흡수·INDEX 명시 선언 제거 (V1 헤더 line 13 정책·InnoDB 자동 INDEX 의존)
-- delivery.claim_id NULLABLE FK + Claim.attachExchangeDelivery 진입점

ALTER TABLE delivery
  ADD COLUMN claim_id BIGINT NULL COMMENT 'EXCHANGE 교환품 Delivery 참조·일반 주문은 NULL·Q13',
  ADD CONSTRAINT fk_delivery_claim FOREIGN KEY (claim_id) REFERENCES claim (id)
      ON DELETE RESTRICT ON UPDATE CASCADE;

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- ALTER TABLE delivery DROP FOREIGN KEY fk_delivery_claim;
-- ALTER TABLE delivery DROP COLUMN claim_id;
-- ============================================================
