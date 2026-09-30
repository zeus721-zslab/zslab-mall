package com.zslab.mall.common.util;

/**
 * 이메일 마스킹 공용 유틸(정산 셀러 연락처에서 추출·Track 106-4). 로컬파트 앞 {@value #VISIBLE_PREFIX_LENGTH}자만 노출한다(예:
 * {@code ab***@domain}). 로컬파트가 그 이하로 짧으면 로컬파트 전부를 가린다.
 */
public final class EmailMasker {

    private static final int VISIBLE_PREFIX_LENGTH = 2;
    private static final String MASK = "***";

    private EmailMasker() {
    }

    /**
     * 이메일을 마스킹한다. null·빈 값은 그대로 null을 돌려주고(호출부가 "없음"을 구분할 수 있도록), '@'가 없거나 맨 앞이면 전부 가린다.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return MASK;
        }
        String local = email.substring(0, at);
        String visible = local.length() > VISIBLE_PREFIX_LENGTH ? local.substring(0, VISIBLE_PREFIX_LENGTH) : "";
        return visible + MASK + email.substring(at);
    }
}
