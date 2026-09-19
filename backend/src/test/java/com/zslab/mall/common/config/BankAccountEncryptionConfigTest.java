package com.zslab.mall.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 키 로딩 fail-fast(Track 89-F·D-188). 빈 팩토리와 같은 {@link BankAccountEncryptionConfig#createEncryptor}를 직접 검증한다 — prod에서
 * env 미주입은 {@code ${BANK_ACCOUNT_ENCRYPTION_KEY}} placeholder 해석 실패로 컨텍스트가 뜨지 않고(JWT 동형), 값이 있어도 형식이 틀리면
 * 여기서 IllegalArgumentException으로 기동이 중단된다. 메시지에 키 값은 실리지 않는다.
 */
class BankAccountEncryptionConfigTest {

    @Test
    @DisplayName("Base64 32바이트 키 → 암복호기 생성(application.yml 로컬 더미 키 형식과 동일)")
    void validKey_createsEncryptor() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        AesGcmTextEncryptor encryptor = BankAccountEncryptionConfig.createEncryptor(key);
        assertThat(encryptor.decrypt(encryptor.encrypt("x"))).isEqualTo("x");
        assertThat(BankAccountEncryptionConfig.createEncryptor("bG9jYWwtZGV2LWR1bW15LWJhbmstYWNjb3VudC1rZXk=")).isNotNull();
    }

    @Test
    @DisplayName("공백 키 → 기동 실패 메시지(env 이름 명시)")
    void blankKey_fails() {
        assertThatThrownBy(() -> BankAccountEncryptionConfig.createEncryptor(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BANK_ACCOUNT_ENCRYPTION_KEY").hasMessageContaining("비어");
        assertThatThrownBy(() -> BankAccountEncryptionConfig.createEncryptor(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Base64 아님 → 형식 오류 메시지 / 32바이트 아님 → 길이 오류 메시지(현재 길이 병기·키 값 미포함)")
    void malformedKey_fails() {
        assertThatThrownBy(() -> BankAccountEncryptionConfig.createEncryptor("not base64 ???"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Base64 형식");
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> BankAccountEncryptionConfig.createEncryptor(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트").hasMessageContaining("현재 16바이트")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(shortKey));
    }
}
