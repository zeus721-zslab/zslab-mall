package com.zslab.mall.common.exception;

/**
 * 공개 관리자 데모 세션(데모 로그인 대행으로 발급된 토큰)이 계정·권한 변경 또는 데모 데이터 생성을 요청했을 때 발생한다(최종 점검 K1).
 * 대상이 누구든 같다. 전역 예외 핸들러가 HTTP 403 {@code DEMO_SESSION_RESTRICTED}로 응답한다.
 */
public class PublicDemoSessionRestrictedException extends RuntimeException {

    public PublicDemoSessionRestrictedException(String message) {
        super(message);
    }
}
