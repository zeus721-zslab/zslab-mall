package com.zslab.mall.category.exception;

/**
 * 카테고리 미존재 — 상품 등록 등에서 참조한 categoryId에 해당하는 Category가 없을 때 발생한다(Track 39 provisioning).
 * 전역 예외 핸들러는 HTTP 404(NOT_FOUND)로 응답한다(매핑 배선은 P5·UserNotFoundException 404 선례 정합).
 */
public class CategoryNotFoundException extends RuntimeException {

    public CategoryNotFoundException(String message) {
        super(message);
    }
}
