package com.zslab.mall.claim.enums;

/**
 * 클레임 거부 사유 코드(Track 80 D-169). 요청 사유({@link ClaimReasonCode})와 별개로 관리자·셀러의 거부 근거를 남긴다.
 *
 * <p>유형 공용이나 {@link #ALREADY_SHIPPED}는 CANCEL 전용이다(반품·교환은 이미 발송된 뒤 요청되므로 의미가 없음).
 * 전용 여부는 {@link #isApplicableTo}로 판정하며 위반은 도메인 검증({@code Claim.reject})이 400으로 처리한다.
 * claim.reject_reason_code 컬럼은 varchar(50) + CHECK이며 본 enum 이름({@code name()})을 저장한다.
 */
public enum ClaimRejectReasonCode {
    /** 이미 발송됨(CANCEL 전용·C2 "발송 후 거부 → 송장 등록" 경로). */
    ALREADY_SHIPPED("이미 발송됨"),
    /** 정책상 취소·반품·교환 불가(기간 경과·비대상 상품 등). */
    OUT_OF_POLICY("정책상 불가"),
    /** 구매자 요청 철회. */
    BUYER_WITHDRAWN("구매자 철회"),
    /** 기타(메모 병기 권장). */
    OTHER("기타");

    /** 구매자 알림(SMS 본문)용 한글 라벨. 화면 라벨은 프론트 constants가 별도 보유한다. */
    private final String label;

    ClaimRejectReasonCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 해당 클레임 유형에 사용할 수 있는 사유인지 판정한다. ALREADY_SHIPPED만 CANCEL 한정이다. */
    public boolean isApplicableTo(ClaimType claimType) {
        if (this == ALREADY_SHIPPED) {
            return claimType == ClaimType.CANCEL;
        }
        return true;
    }
}
