package com.zslab.mall.category.controller.request;

import com.zslab.mall.settlement.service.CommissionRateResolver;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 관리자 카테고리 수정 요청(Track 89-C D-185·PUT 전체 치환). 3필드를 항상 전부 받는다 — Jackson record는 "필드 생략"과 "null 명시"를
 * 구분하지 못해 부분 수정으로는 commissionRate를 미설정(NULL)으로 환원할 수 없기 때문이다.
 *
 * <p>commissionRate는 basis-point(1000 = 10.00%)·null = 미설정(플랫폼 기본율). 범위는 {@link CommissionRateResolver} 상수(0~10000)와
 * 같다 — 범위 밖 값이 저장되면 그 카테고리 상품의 체크아웃이 판정 단계에서 차단되므로 저장 전에 400으로 막는다.
 * reason은 commissionRate가 실제로 바뀔 때만 필수이며 그 판정은 Service diff가 한다(형식 검증은 길이만).
 */
public record UpdateCategoryRequest(
        @NotBlank @Size(max = 200) String displayName, // SoT: Category.displayName @Column(length=200)
        @NotNull @PositiveOrZero Integer sortOrder,
        @Min(CommissionRateResolver.MIN_COMMISSION_RATE) @Max(CommissionRateResolver.MAX_COMMISSION_RATE) Integer commissionRate,
        @Size(max = 200) String reason) {
}
