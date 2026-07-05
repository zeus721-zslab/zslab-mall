package com.zslab.mall.audit.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 감사 diff 맵에서 민감정보 필드를 마스킹한다(Track 52 Phase 1·AUD-2·결정3 역할 분리).
 *
 * <p><b>현 소비처 민감필드 없음</b>: 이번 트랙 3소비처(정산 상태전이·상품 승인·등급 산정)의 변경 필드에는 민감정보가 없어
 * 실질적으로 통과(no-op)한다. 다만 AUD-2 마스킹 훅(진입점)은 여기서 확정한다 — 후행 소비처(계좌·결제 토큰 등)가 추가돼
 * 해당 필드명이 diff에 나타나면 자동으로 마스킹된다. 과잉 규칙 신설을 피해 명백한 민감 필드명만 최소로 등록한다(AUD-2 §D-11).
 *
 * <p>판정은 최상위 필드명 정확 매칭이다(대소문자 구분). 매칭 시 해당 필드 값 전체를 {@link #MASK}로 치환한다
 * (before/after 노출 자체를 차단).
 */
@Component
public class Masker {

    /** 마스킹 표식(값 자체를 노출하지 않음). */
    public static final String MASK = "***MASKED***";

    /** 민감 필드명 집합(비밀번호·결제 토큰·계좌번호·주민번호 계열·AUD-2). */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password",
            "passwordHash",
            "accountNumber",
            "bankAccountNumber",
            "residentRegistrationNumber",
            "paymentToken",
            "pgToken",
            "cardNumber");

    /**
     * diff 맵의 민감 필드 값을 마스킹한 새 맵을 반환한다(원본 불변). 민감 필드가 없으면 내용 동일한 맵을 반환한다.
     * {@code null}·빈 맵은 빈 맵으로 반환한다.
     */
    public Map<String, Object> mask(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            boolean sensitive = SENSITIVE_FIELDS.contains(entry.getKey());
            masked.put(entry.getKey(), sensitive ? MASK : entry.getValue());
        }
        return masked;
    }
}
