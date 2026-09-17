package com.zslab.mall.user.service;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * 임시 비밀번호 생성(Track 84). SecureRandom·12자·영문 대소문자+숫자에서 혼동 문자(0·O·1·l·I)를 제외한다.
 * 길이 12는 {@link com.zslab.mall.user.policy.PasswordPolicy} 최소 8자를 충족한다.
 */
@Component
public class TemporaryPasswordGenerator {

    static final int LENGTH = 12;
    /** 영문 대소문자+숫자 중 0·O·1·l·I 제외. */
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final SecureRandom secureRandom = new SecureRandom();

    public String generate() {
        StringBuilder builder = new StringBuilder(LENGTH);
        for (int index = 0; index < LENGTH; index++) {
            builder.append(ALPHABET.charAt(secureRandom.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
