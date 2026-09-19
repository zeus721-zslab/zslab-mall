package com.zslab.mall.common.config;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 셀러 정산계좌 암호화 키 로딩(Track 89-F·D-188). {@code bank-account.encryption-key}(env {@code BANK_ACCOUNT_ENCRYPTION_KEY}).
 *
 * <p>키 형식 = <b>raw 32바이트를 Base64로 인코딩한 44자</b>. JWT 시크릿(UTF-8 문자열 바이트)과 달리 Base64를 택한 이유: AES 키는
 * 정확히 32바이트의 균일 난수여야 하는데 문자열 키는 charset·길이가 모호하고 엔트로피가 낮다. Base64면 CSPRNG 32바이트를 손실 없이
 * env 한 줄에 담을 수 있다(생성 명령은 .env.example 주석).
 *
 * <p>fail-fast(JWT 동형): base application.yml은 로컬 더미 기본값, application-prod.yml은 기본값 없이 {@code ${BANK_ACCOUNT_ENCRYPTION_KEY}}
 * → prod에서 env 미주입이면 placeholder 해석 실패로 기동 중단. 값이 있어도 Base64가 아니거나 32바이트가 아니면 여기서 예외로 기동 중단.
 * 잘못된 키로 기동하면 기존 암호문을 읽을 수 없고 새 데이터는 다른 키로 써지므로, 기동 자체를 막는 것이 데이터 보호다.
 */
@Configuration
public class BankAccountEncryptionConfig {

    @Bean
    public AesGcmTextEncryptor bankAccountEncryptor(@Value("${bank-account.encryption-key}") String base64Key) {
        return createEncryptor(base64Key);
    }

    /**
     * Base64 키 → 암복호기. 빈 팩토리와 테스트가 공유한다.
     *
     * @throws IllegalArgumentException 공백·Base64 아님·32바이트 아님(메시지에 원인 명시·키 값은 싣지 않음)
     */
    public static AesGcmTextEncryptor createEncryptor(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException(
                    "bank-account.encryption-key(BANK_ACCOUNT_ENCRYPTION_KEY)가 비어 있습니다. raw 32바이트를 Base64로 인코딩한 값을 주입하세요.");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "bank-account.encryption-key(BANK_ACCOUNT_ENCRYPTION_KEY)가 Base64 형식이 아닙니다.", exception);
        }
        if (keyBytes.length != AesGcmTextEncryptor.KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("bank-account.encryption-key(BANK_ACCOUNT_ENCRYPTION_KEY)는 Base64 디코드 시 정확히 "
                    + AesGcmTextEncryptor.KEY_LENGTH_BYTES + "바이트여야 합니다. 현재 " + keyBytes.length + "바이트.");
        }
        return new AesGcmTextEncryptor(keyBytes);
    }
}
