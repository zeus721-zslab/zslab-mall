package com.zslab.mall.claim.exception;

/**
 * 일괄 승인 항목의 현재 제안이 승인이 아닐 때 발생한다(D-250 서버 가드). 일괄 승인 서비스가 항목 실패로 흡수하므로 HTTP 매핑은 없다.
 */
public class ClaimSuggestionMismatchException extends RuntimeException {

    public ClaimSuggestionMismatchException(String message) {
        super(message);
    }
}
