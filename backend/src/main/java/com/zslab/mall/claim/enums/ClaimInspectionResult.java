package com.zslab.mall.claim.enums;

/**
 * 반품 검수 결과(Track 81-A D-170). DDL {@code claim.inspection_result} CHECK 정합·DTO 검증 단일 소스.
 *
 * <p>PASS는 환불 개시(재입고 여부는 {@code Claim.restock}), FAIL은 검수 불합격 거부(APPROVED → REJECTED 예외 전이·품목 원복·재발송)다.
 */
public enum ClaimInspectionResult {
    PASS,
    FAIL
}
