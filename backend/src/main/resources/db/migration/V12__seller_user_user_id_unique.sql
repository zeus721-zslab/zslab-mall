-- V12: seller_user UNIQUE 복합(seller_id, user_id) → user_id 단독 전환 (Track 36 γ·D-121)
-- 작성일: 2026-07-04
-- 참조: decisions.md D-121·docs/track-36/RECON.md §3
-- 목적: "1 user = 1 seller" 강제 — user_id 단독 UNIQUE로 판매자 구성원을 user 계정에 1:1 귀속.
--       (이후 Phase resolver가 user.id→seller.id를 단건 해소하기 위한 선결 제약)
-- 데이터 안전(RECON §5): 프로덕션 seller_user INSERT 경로 부재·중복 user_id fixture 부재 → 기존 데이터/테스트 충돌 없음.
--
-- 트랩·설계(RECON §3 실측·docker MariaDB 11.4): 복합 uk_seller_user (seller_id, user_id)는
--   FK fk_seller_user_seller의 유일 지원 인덱스를 겸한다(seller_id가 leftmost). 따라서 bare
--   DROP INDEX uk_seller_user 는 ERROR 1553 (Cannot drop index ... needed in a foreign key
--   constraint)로 실패한다. → seller_id 보상 인덱스(idx_seller_user_seller)를 같은 ALTER에서
--   선행 추가해 FK 지원을 보존한 뒤 복합 UNIQUE를 제거하고 user_id 단독 UNIQUE를 추가한다(단일 atomic ALTER).
--   적용 후 인덱스: PRIMARY·uk_seller_user_user_id(user_id UNIQUE)·idx_seller_user_seller(seller_id)·fk_seller_user_role.
ALTER TABLE seller_user
  ADD INDEX idx_seller_user_seller (seller_id),
  DROP INDEX uk_seller_user,
  ADD UNIQUE KEY uk_seller_user_user_id (user_id);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행한다. 단순 역순은 대칭 트랩(ERROR 1553)에 걸린다:
--   forward에서 auto fk_seller_user_user(user_id)가 uk_seller_user_user_id에 흡수되므로,
--   user_id 지원 인덱스를 먼저 복원해야 uk_seller_user_user_id 를 제거할 수 있다.
-- 아래 4-op는 V1 원본 인덱스 레이아웃을 정확히 복원한다(RECON §3.4 실측).
-- ※ 복합 uk_seller_user 재생성은 현 데이터에 중복 (seller_id, user_id) 쌍이 없어야 성공.
--
-- ALTER TABLE seller_user
--   ADD INDEX fk_seller_user_user (user_id),
--   DROP INDEX uk_seller_user_user_id,
--   DROP INDEX idx_seller_user_seller,
--   ADD UNIQUE KEY uk_seller_user (seller_id, user_id);
-- ============================================================