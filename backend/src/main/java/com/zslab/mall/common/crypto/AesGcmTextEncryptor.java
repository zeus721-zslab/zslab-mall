package com.zslab.mall.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 문자열 암복호화(Track 89-F·D-188·SLR-2). 저장 형식은 {@code v1:} + Base64(IV ‖ ciphertext ‖ tag)다.
 *
 * <p>설계 근거:
 * <ul>
 *   <li>GCM = 기밀성 + 무결성(인증 태그 16바이트). 키가 다르거나 암호문이 변조되면 복호화가 예외로 실패한다(조용한 오복호 없음).</li>
 *   <li>IV는 암호화마다 {@link SecureRandom} 12바이트 — 같은 평문도 매번 다른 암호문이 되므로 등호·LIKE 검색은 불가하다
 *       (정찰: account_number 조건 조회 0건이라 비용 없음).</li>
 *   <li>{@code v1:} 접두사 = (a) 백필 멱등(이미 암호화된 행 식별) (b) 키 로테이션 시 {@code v2:} 병행 복호 출구 (c) 평문 잔존 감지.</li>
 *   <li>{@link #decrypt}는 <b>strict</b>: 접두사가 없으면 평문으로 통과시키지 않고 예외를 던진다. 백필(V33) 이후 평문이 남는 경로는
 *       raw SQL뿐이며 그것은 SLR-2 위반 신호이므로 fail-closed가 맞다.</li>
 * </ul>
 * 14자 입력 → 12 + 14 + 16 = 42바이트 → Base64 56자 + 접두사 3자 = 59자. VARCHAR(255)에 30자 입력도 여유(약 80자).
 *
 * <p>spring-security-crypto {@code AesBytesEncryptor}(GCM)를 쓰지 않고 JDK {@link Cipher}를 직접 쓰는 이유: 접두사·IV 길이·인코딩을
 * 이 클래스가 명시적으로 소유해야 로테이션·톰스톤(D-23 B-d4 정정) 형식을 같은 곳에서 관리할 수 있다. 신규 의존성 없음.
 */
public final class AesGcmTextEncryptor {

    /** 저장 형식 버전 접두사. 키 로테이션 시 v2를 추가하고 복호는 접두사로 분기한다. */
    public static final String VERSION_PREFIX = "v1:";
    /** AES-256 = 32바이트 키. */
    public static final int KEY_LENGTH_BYTES = 32;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12; // NIST SP 800-38D 권장 GCM IV 길이
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * @param keyBytes raw 32바이트 키
     * @throws IllegalArgumentException 키가 null이거나 32바이트가 아닐 때
     */
    public AesGcmTextEncryptor(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("AES-256 키는 정확히 " + KEY_LENGTH_BYTES + "바이트여야 합니다. 현재 "
                    + (keyBytes == null ? "null" : keyBytes.length + "바이트") + ".");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 평문을 {@code v1:Base64(IV‖ciphertext‖tag)}로 암호화한다.
     *
     * @throws IllegalArgumentException 평문이 null일 때
     */
    public String encrypt(String plainText) {
        if (plainText == null) {
            throw new IllegalArgumentException("암호화할 평문이 null입니다.");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM 암호화 실패", exception);
        }
    }

    /**
     * {@code v1:} 암호문을 복호화한다(strict).
     *
     * @throws IllegalStateException 접두사가 없거나(평문·미지원 버전) Base64·길이·인증 태그 검증에 실패했을 때(키 불일치·변조)
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(VERSION_PREFIX)) {
            // 값 자체는 메시지에 싣지 않는다(평문이 남아 있는 경우 로그에 계좌번호가 노출된다).
            throw new IllegalStateException("암호화 형식이 아닙니다(" + VERSION_PREFIX + " 접두사 없음). 백필(V33) 누락 또는 raw SQL로 평문이 저장된 행입니다.");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(stored.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("암호문 Base64 디코딩 실패", exception);
        }
        if (payload.length < IV_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
            throw new IllegalStateException("암호문 길이가 IV+태그 최소 길이보다 짧습니다: " + payload.length + "바이트");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH_BYTES);
        byte[] cipherText = Arrays.copyOfRange(payload, IV_LENGTH_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM 복호화 실패(키 불일치 또는 암호문 변조)", exception);
        }
    }

    /** 저장값이 {@code v1:} 형식인지(백필 멱등 판정·테스트용). */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(VERSION_PREFIX);
    }
}
