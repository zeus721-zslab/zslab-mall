package com.zslab.mall.common.exception;

/**
 * 요청 형식·파싱 오류(예: X-Buyer-Id BIGINT 파싱 실패)일 때 발생한다(§2·§14). 전역 예외 핸들러가 HTTP 400
 * {@code MALFORMED_REQUEST}로 응답한다. JSON·타입 변환 실패 등 Spring 표준 파싱 예외도 같은 코드로 매핑한다.
 */
public class MalformedRequestException extends RuntimeException {

    public MalformedRequestException(String message) {
        super(message);
    }
}
