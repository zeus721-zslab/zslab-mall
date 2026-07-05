package com.zslab.mall.category.exception;

/**
 * 카테고리 중복 — 같은 parent 스코프에 동일 display_name 활성 카테고리를 다시 생성하려 할 때 발생한다(Track 46).
 * DB 제약(uk_category_dedup_key·V13)을 최종 방어선으로 삼아 {@code saveAndFlush}의
 * {@code DataIntegrityViolationException}을 본 예외로 변환한다. 전역 예외 핸들러가 HTTP 409(CONFLICT)로 응답한다.
 */
public class CategoryDuplicateException extends RuntimeException {

    public CategoryDuplicateException(String message) {
        super(message);
    }
}
