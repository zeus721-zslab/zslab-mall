package com.zslab.mall.user.controller.response;

/**
 * 임시 비밀번호 발급 응답(D-204). 평문은 이 응답 본문으로 관리자 화면에 1회만 전달되며(SMS 병행), 서버는 해시만 보관하므로 재조회 경로가
 * 없다. 로그·감사에 실수로 찍히지 않도록 {@code toString}은 평문을 가린다.
 */
public record TemporaryPasswordResponse(String temporaryPassword) {

    private static final String MASK = "****";

    @Override
    public String toString() {
        return "TemporaryPasswordResponse[temporaryPassword=" + MASK + "]";
    }
}
