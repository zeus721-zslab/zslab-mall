-- V48: 구매자 비밀번호 재설정 토큰 — password_reset_token (D-269 · 퀄리티 v3 UX-01)
-- 작성일: 2026-10-07
-- 목적: 이메일 링크로 보낸 재설정 토큰의 해시·만료·사용 시각. 토큰 원문은 저장하지 않는다(SHA-256 hex 64자만).
--
-- 설계:
--   상태 컬럼 없음 — 유효 = used_at IS NULL AND expires_at > now. 사용(확정)은 조건부 UPDATE(used_at IS NULL AND expires_at > now)로
--   1회만 성공한다. 새 요청 시 같은 회원의 미사용 토큰은 행 삭제(HARD)로 무효화한다. 만료·사용 행 정리 배치는 두지 않는다(행 수가 요청 수에 비례·조회는 해시 UK).
--   user_id는 FK RESTRICT(회원은 물리 삭제하지 않는다·soft-delete만).
-- 데이터 안전: 신규 테이블 1개 — 기존 행 변경 없음.

CREATE TABLE password_reset_token (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL COMMENT '재설정 대상 User.id',
  token_hash  CHAR(64)     NOT NULL COMMENT '토큰 SHA-256 hex(원문 미저장)',
  expires_at  DATETIME(6)  NOT NULL COMMENT '만료 시각(발급 + 30분)',
  used_at     DATETIME(6)  NULL     COMMENT '확정(사용) 시각·NULL = 미사용',
  created_at  DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  -- 확정 요청은 토큰 해시 등치 조회 1회로 행을 찾는다.
  UNIQUE KEY uk_password_reset_token_hash (token_hash),
  -- 새 요청 시 같은 회원의 미사용 토큰 삭제(user_id 등치)가 FK 인덱스를 탄다.
  CONSTRAINT fk_password_reset_token_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='구매자 비밀번호 재설정 토큰(해시만·1회 사용·D-269)';

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 토큰은 30분짜리 일회용이라 삭제해도 회원·비밀번호 데이터에 영향이 없다(진행 중 재설정 링크만 무효가 된다).
-- 코드를 이전 버전으로 되돌릴 때는 테이블을 남겨 둬도 무방하다(이전 코드는 참조하지 않음 · ddl-auto=validate는 미매핑 테이블을 검사하지 않는다).
--
-- DROP TABLE password_reset_token;
-- ============================================================
