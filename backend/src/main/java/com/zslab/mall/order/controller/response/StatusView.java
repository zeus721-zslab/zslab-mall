package com.zslab.mall.order.controller.response;

/**
 * UI 노출 status 표현 객체({@code {code, label}}·§12·D-46). label은 원래 운영자 편집 Code 테이블 조회 결과이나,
 * Track 4 시점 Code 도메인이 없어 §12·§15 명시 fallback대로 {@code label = code}로 채운다(Track 7 Code 도입 시 실 라벨 반영).
 */
public record StatusView(String code, String label) {

    /** enum status를 {@code {code, label}}로 변환한다(label=code fallback·§12). */
    public static StatusView of(Enum<?> status) {
        return new StatusView(status.name(), status.name());
    }

    /** enum에 없는 합성 code(예: INITIATE_FAILED·§7)를 표현한다(label=code fallback). */
    public static StatusView ofCode(String code) {
        return new StatusView(code, code);
    }
}
