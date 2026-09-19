package com.zslab.mall.seller.converter;

import com.zslab.mall.common.crypto.AesGcmTextEncryptor;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * {@code seller_bank_account.account_number} 투명 암복호화(Track 89-F·D-188 §1-A α·SLR-2).
 *
 * <p>엔티티 필드는 항상 평문, DB 컬럼은 항상 {@code v1:} 암호문이다. 읽는 쪽(정산 상세·셀러 상세 끝 4자리)은 코드 무변경으로 평문을 받는다.
 * 복호화는 strict — 접두사 없는 값(백필 전 평문·raw SQL 삽입)은 {@link IllegalStateException}으로 실패한다(fail-closed).
 *
 * <p>Spring 빈 주입: Spring Boot가 Hibernate {@code BEAN_CONTAINER}에 {@code SpringBeanContainer}를 등록하므로 {@code @Converter}
 * 클래스의 생성자 주입이 동작한다({@code AccountNumberEncryptionConverterIntegrationTest}가 저장→조회 왕복으로 검증).
 * {@code autoApply=false}: 계좌번호 필드에만 명시 적용한다(다른 String 컬럼 오적용 방지).
 */
@Component
@Converter
public class AccountNumberEncryptionConverter implements AttributeConverter<String, String> {

    private final AesGcmTextEncryptor encryptor;

    public AccountNumberEncryptionConverter(AesGcmTextEncryptor bankAccountEncryptor) {
        this.encryptor = bankAccountEncryptor;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : encryptor.decrypt(dbData);
    }
}
