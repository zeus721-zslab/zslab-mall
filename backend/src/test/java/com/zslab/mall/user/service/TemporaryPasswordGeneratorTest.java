package com.zslab.mall.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zslab.mall.user.policy.PasswordPolicy;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 임시 비밀번호 생성 단위 테스트(Track 84): 12자·허용 문자 집합·혼동 문자(0·O·1·l·I) 제외·정책 충족·반복 생성 시 상이. */
class TemporaryPasswordGeneratorTest {

    private static final int SAMPLE_COUNT = 200;

    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

    @Test
    @DisplayName("12자·영문 대소문자+숫자 집합 내·혼동 문자 미포함·PasswordPolicy 통과")
    void generate_lengthCharsetPolicy() {
        PasswordPolicy policy = new PasswordPolicy();
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            String password = generator.generate();
            assertThat(password).hasSize(TemporaryPasswordGenerator.LENGTH)
                    .matches("^[A-Za-z0-9]+$")
                    .doesNotContainPattern("[0O1lI]");
            policy.validate(password);
        }
    }

    @Test
    @DisplayName("반복 생성 결과가 서로 다르다(SecureRandom·중복 없음)")
    void generate_distinct() {
        Set<String> generated = new HashSet<>();
        for (int index = 0; index < SAMPLE_COUNT; index++) {
            generated.add(generator.generate());
        }
        assertThat(generated).hasSize(SAMPLE_COUNT);
    }
}
