-- V23: claim 거부 사유 컬럼 + 관리자 목록 인덱스 (Track 80·D-169)
-- 작성일: 2026-09-16
-- 참조: decisions.md D-169(거부 사유 코드 필수·메모 선택·유형 공용 enum ClaimRejectReasonCode)

-- 거부 사유는 거부 시점에만 채워지므로 nullable. 기존 행(REJECTED 포함)은 NULL 유지(백필 없음).
-- 코드 값은 CHECK로 잠근다(4층위 잠금 ① DB). Java enum com.zslab.mall.claim.enums.ClaimRejectReasonCode와 1:1.
ALTER TABLE claim
  ADD COLUMN reject_reason_code VARCHAR(50) NULL COMMENT '거부 사유 코드·ClaimRejectReasonCode(D-169)' AFTER refund_amount,
  ADD COLUMN reject_memo VARCHAR(500) NULL COMMENT '거부 메모(선택·500자)' AFTER reject_reason_code,
  ADD CONSTRAINT chk_claim_reject_reason_code
    CHECK (reject_reason_code IS NULL
           OR reject_reason_code IN ('ALREADY_SHIPPED', 'OUT_OF_POLICY', 'BUYER_WITHDRAWN', 'OTHER'));

-- 관리자 클레임 목록(GET /api/v1/admin/claims)은 유형 탭 필터 + requested_at 내림차순이 기본이다.
-- 기존 인덱스는 ix_claim_status(V1)뿐이라 유형 탭·정렬은 풀스캔+filesort가 되므로 복합 인덱스를 둔다.
CREATE INDEX ix_claim_type_requested_at ON claim (type, requested_at);

-- ============================================================
-- ROLLBACK (보상 마이그레이션·수동 실행용·Flyway OSS는 undo 미지원)
-- 운영 적용 후 회귀 필요 시 아래 SQL을 수동 실행:
--
-- DROP INDEX ix_claim_type_requested_at ON claim;
-- ALTER TABLE claim DROP CONSTRAINT chk_claim_reject_reason_code;
-- ALTER TABLE claim DROP COLUMN reject_memo;
-- ALTER TABLE claim DROP COLUMN reject_reason_code;
-- ============================================================
