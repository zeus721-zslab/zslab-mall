package com.zslab.mall.cart.controller.request;

import jakarta.validation.constraints.NotNull;

/**
 * 장바구니 전체 selected 토글 요청(Track 45). userId는 인증 컨텍스트 해소·요청 제외. buyer의 전 품목을 일괄 선택/해제한다.
 *
 * @param selected 선택 여부(true 전체 선택·false 전체 해제)
 */
public record CartSelectAllRequest(
        @NotNull Boolean selected) {
}
