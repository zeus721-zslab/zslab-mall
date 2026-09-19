package com.zslab.mall.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link AesGcmTextEncryptor} 단위(Track 89-F·D-188): 왕복·랜덤 IV·strict 복호·키 불일치·키 길이. 계좌 실값이 아닌 테스트 상수만 쓴다. */
class AesGcmTextEncryptorTest {

    private static final byte[] KEY_A = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_B = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final String PLAIN = "110-000-000999";

    @Test
    @DisplayName("암호화→복호화 왕복 · 출력은 v1: 접두사 + Base64 · VARCHAR(255) 내")
    void roundTrip() {
        AesGcmTextEncryptor encryptor = new AesGcmTextEncryptor(KEY_A);
        String stored = encryptor.encrypt(PLAIN);
        assertThat(stored).startsWith(AesGcmTextEncryptor.VERSION_PREFIX);
        assertThat(AesGcmTextEncryptor.isEncrypted(stored)).isTrue();
        assertThat(stored.length()).isLessThan(255);
        assertThat(stored).doesNotContain(PLAIN);
        assertThat(encryptor.decrypt(stored)).isEqualTo(PLAIN);
        // 30자 상한 입력도 여유
        assertThat(encryptor.encrypt("9".repeat(30)).length()).isLessThan(255);
    }

    @Test
    @DisplayName("같은 평문을 두 번 암호화하면 IV가 달라 암호문이 다르다(등호 검색 불가·의도)")
    void randomIv_differentCiphertexts() {
        AesGcmTextEncryptor encryptor = new AesGcmTextEncryptor(KEY_A);
        String first = encryptor.encrypt(PLAIN);
        String second = encryptor.encrypt(PLAIN);
        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(first)).isEqualTo(encryptor.decrypt(second));
    }

    @Test
    @DisplayName("strict: v1: 접두사 없는 값(평문·다른 버전)은 통과시키지 않고 예외 · 메시지에 값 미포함")
    void decrypt_withoutPrefix_throws() {
        AesGcmTextEncryptor encryptor = new AesGcmTextEncryptor(KEY_A);
        assertThatThrownBy(() -> encryptor.decrypt(PLAIN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v1:")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(PLAIN));
        assertThatThrownBy(() -> encryptor.decrypt("v2:abc")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptor.decrypt(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptor.decrypt("v1:not-base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptor.decrypt("v1:AAAA")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("길이");
    }

    @Test
    @DisplayName("다른 키·변조된 암호문은 GCM 태그 검증 실패로 예외(조용한 오복호 없음)")
    void decrypt_wrongKeyOrTampered_throws() {
        String stored = new AesGcmTextEncryptor(KEY_A).encrypt(PLAIN);
        assertThatThrownBy(() -> new AesGcmTextEncryptor(KEY_B).decrypt(stored))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("키 불일치");
        char last = stored.charAt(stored.length() - 1);
        String tampered = stored.substring(0, stored.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> new AesGcmTextEncryptor(KEY_A).decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("키는 정확히 32바이트 · null·평문 null 거부")
    void keyLength_validation() {
        assertThatThrownBy(() -> new AesGcmTextEncryptor(Arrays.copyOf(KEY_A, 16)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32바이트");
        assertThatThrownBy(() -> new AesGcmTextEncryptor(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmTextEncryptor(KEY_A).encrypt(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
