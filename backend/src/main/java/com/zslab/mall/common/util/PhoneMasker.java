package com.zslab.mall.common.util;

/**
 * 휴대폰 번호 마스킹 공용 유틸(Track 80 D-169). 로그·응답에 번호를 남길 때 앞 3자리·뒤 4자리만 노출한다(예: {@code 010-****-1234}).
 *
 * <p>입력의 하이픈·공백은 무시하고 숫자만 본다. 숫자가 {@value #MIN_DIGITS}자리 미만이면 형식을 판단할 수 없으므로 전부 가린다.
 */
public final class PhoneMasker {

    private static final int PREFIX_LENGTH = 3;
    private static final int SUFFIX_LENGTH = 4;
    private static final int MIN_DIGITS = PREFIX_LENGTH + SUFFIX_LENGTH + 1;
    private static final String MASKED_MIDDLE = "-****-";
    private static final String FULLY_MASKED = "***-****-****";

    private PhoneMasker() {
    }

    /**
     * 번호를 마스킹한다. null·빈 값은 그대로 null을 돌려준다(호출부가 "없음"을 구분할 수 있도록).
     */
    public static String mask(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        if (digits.length() < MIN_DIGITS) {
            return FULLY_MASKED;
        }
        return digits.substring(0, PREFIX_LENGTH) + MASKED_MIDDLE + digits.substring(digits.length() - SUFFIX_LENGTH);
    }
}
