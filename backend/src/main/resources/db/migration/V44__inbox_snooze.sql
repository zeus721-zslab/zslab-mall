-- V44: 운영 인박스 보류 — inbox_snooze (D-248 · 운영 인박스 P1a)
-- 작성일: 2026-10-01
-- 참조: docs/track-ops-inbox/recon-report.md · decisions.md D-248(보류 = 만료형 오버레이)
-- 목적: 운영자·셀러 구성원이 인박스 항목을 "언제까지 안 보이게" 보류한 기록. 대기 여부는 원천 상태에서 파생하고(D-220),
--       이 테이블은 수집 결과에서 until_at 전까지 해당 항목을 빼는 데만 쓴다. 원천이 해소되면 보류 행과 무관하게 항목이 사라진다.
--
-- 설계:
--   소유자·유형·대상 1쌍당 행 1개(uk). 다시 보류하면 until_at·reason을 덮어쓴다. 해제는 행 삭제(HARD).
--   만료 행(until_at <= now)은 수집이 무시하며 정리 배치는 두지 않는다.
--   owner_user_id = 보류한 User.id(논리참조·FK 미적용·행위자 컬럼 관례). 셀러 구성원별로 따로 보류한다.
--   item_type = VARCHAR + CHECK — 4층위 enum 잠금 (1)DB · Java InboxItemType · 요청 DTO enum 바인딩(FE 상수는 D-248 §8 이월).
--   item_ref = 원천 도메인 API 식별자(publicId 또는 id 문자열). 가장 긴 값은 public_id CHAR(30).
-- 데이터 안전: 신규 테이블 1개 — 기존 행 변경 없음.

CREATE TABLE inbox_snooze (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  owner_user_id   BIGINT        NOT NULL COMMENT '보류한 User.id 논리참조(FK 미적용)',
  item_type       VARCHAR(30)   NOT NULL COMMENT '인박스 항목 유형(InboxItemType)',
  item_ref        VARCHAR(40)   NOT NULL COMMENT '원천 식별자(publicId 또는 id 문자열)',
  until_at        DATETIME(6)   NOT NULL COMMENT '이 시각 전까지 수집에서 제외',
  reason          VARCHAR(200)  NOT NULL COMMENT '보류 사유',
  created_at      DATETIME(6)   NOT NULL,
  PRIMARY KEY (id),
  -- 수집 NOT EXISTS(owner·type·ref 등치 + until_at 범위)와 upsert 조회가 이 키를 탄다.
  UNIQUE KEY uk_inbox_snooze_owner_item (owner_user_id, item_type, item_ref),
  CONSTRAINT chk_inbox_snooze_item_type CHECK (item_type IN (
    'CLAIM_REQUESTED','CLAIM_FOLLOWUP','LONG_SHIPPING','INQUIRY_UNANSWERED','SELLER_REVIEW','PRODUCT_APPROVAL',
    'SETTLEMENT_CONFIRM','SETTLEMENT_PAYOUT','RECONCILIATION_OPEN','DELIVERY_READY','QUESTION_UNANSWERED','LOW_STOCK'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='운영 인박스 보류(만료형 오버레이·HARD·D-248)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 보류는 표시 제외 오버레이라 삭제해도 원천 데이터·대기 판정에 영향이 없다.
--
-- DROP TABLE inbox_snooze;
-- ============================================================
