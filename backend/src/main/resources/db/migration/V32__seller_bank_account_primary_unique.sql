-- V32: seller_bank_account 주 계좌 단일 제약 (Track 89-F·D-188·SLR-3)
-- 작성일: 2026-09-18
-- 참조: docs/track-89f/recon-report.md §6 · D-179 §8("계좌 등록 API 도입 시 generated 컬럼 UNIQUE 함께 도입")
-- 목적: "셀러당 is_primary=1 은 최대 1건"을 DB가 강제한다. 지급(SettlementTransitionService.pay)이 주 계좌 id를 스냅샷하므로
--       주 계좌가 2건이면 지급 대상이 모호해진다(현재는 ORDER BY id + WARN으로만 방어).
--
-- 트랩·설계(V13·V29 실측 선례): MariaDB는 부분 유니크 인덱스(WHERE is_primary=1)를 지원하지 않으므로 STORED generated 컬럼
--   primary_seller_id = IF(is_primary=1, seller_id, NULL) 에 UNIQUE를 건다. 비주계좌 행은 NULL이라 UNIQUE 비교에서 제외된다(NULL≠NULL).
--   그런데 generated 식이 참조하는 컬럼(seller_id)에 ON UPDATE CASCADE FK가 있으면 MariaDB가 generated 컬럼 생성을 거부한다
--   (V13:16·V29:12 실측). fk_seller_bank_account_seller 는 V1에서 ON UPDATE CASCADE 였으므로 ① FK를 ON UPDATE RESTRICT로 재생성한 뒤
--   ② 컬럼 ③ UNIQUE 순서로 적용한다. seller.id 는 AUTO_INCREMENT 이고 갱신 경로가 없어 RESTRICT 전환의 동작 차이는 없다
--   (ON DELETE 는 원래 RESTRICT·무변경 → 셀러 삭제 동작 동일).
--
-- 데이터 안전·재실행: ③ UNIQUE 추가는 기존 데이터가 "셀러당 주 계좌 2건 이상"이면 실패한다(운영 반영 P2 사전 점검 SELECT로 원천 차단·
--   docs/track-89f/ops-checklist.md). DDL 은 문장별 auto-commit 이라 ③ 실패 시 ①②는 남는다 → 모든 문장을 IF [NOT] EXISTS 로 멱등하게
--   작성해 중복 정리 → flyway repair → 재기동으로 그대로 재실행할 수 있게 한다(MariaDB 10.0.2+ 지원·로컬 10.11·테스트 11.4).
--   정찰 실측(로컬): 3행·셀러당 1행·위반 0.

-- ① FK ON UPDATE CASCADE → RESTRICT (generated 컬럼 선행 조건)
ALTER TABLE seller_bank_account
  DROP FOREIGN KEY IF EXISTS fk_seller_bank_account_seller;

ALTER TABLE seller_bank_account
  ADD CONSTRAINT fk_seller_bank_account_seller
    FOREIGN KEY IF NOT EXISTS (seller_id) REFERENCES seller (id)
      ON DELETE RESTRICT ON UPDATE RESTRICT;

-- ② 주 계좌 행만 non-null 인 파생 컬럼 (V13 category.dedup_key · V29 settlement_item.dedup_key 선례)
ALTER TABLE seller_bank_account
  ADD COLUMN IF NOT EXISTS primary_seller_id BIGINT
    AS (IF(is_primary = 1, seller_id, NULL)) STORED
    COMMENT '주 계좌 단일 파생키(is_primary=1 행만 seller_id·그 외 NULL·SLR-3 UNIQUE용)';

-- ③ 셀러당 주 계좌 최대 1건
ALTER TABLE seller_bank_account
  ADD UNIQUE KEY IF NOT EXISTS uk_seller_bank_account_primary (primary_seller_id);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- UNIQUE 는 generated 컬럼에 의존하므로 컬럼보다 먼저 제거한다. FK 는 V1 원형(ON UPDATE CASCADE)으로 복원한다.
--
-- ALTER TABLE seller_bank_account
--   DROP INDEX uk_seller_bank_account_primary,
--   DROP COLUMN primary_seller_id;
-- ALTER TABLE seller_bank_account
--   DROP FOREIGN KEY fk_seller_bank_account_seller;
-- ALTER TABLE seller_bank_account
--   ADD CONSTRAINT fk_seller_bank_account_seller FOREIGN KEY (seller_id) REFERENCES seller (id)
--     ON DELETE RESTRICT ON UPDATE CASCADE;
-- ============================================================
