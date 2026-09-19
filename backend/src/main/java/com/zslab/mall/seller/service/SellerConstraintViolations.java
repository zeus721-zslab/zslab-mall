package com.zslab.mall.seller.service;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * 셀러 무결성 위반 판별(Track 89-D 외부 검토 지적 2 반영). {@code uk_seller_business_no}(V1·SLR-1) 위반만 409
 * {@code SELLER_BUSINESS_NO_DUPLICATE}로 변환하고 그 외 무결성 오류(FK·NOT NULL·다른 UK)는 호출부가 원래 예외 경로로 흘려보내야 한다.
 *
 * <p><b>판별 방식의 취약점</b>: 프로젝트에 constraint 이름을 구분하는 선례가 없어(기존 catch 8곳은 flush 직전 INSERT/UPDATE가 위반할 수
 * 있는 제약이 하나뿐이라 무조건 변환) {@code getMostSpecificCause().getMessage()}에 UK 이름이 포함되는지로 판별한다. 이는
 * <b>DB 벤더(MariaDB)·드라이버 메시지 포맷("Duplicate entry '…' for key 'uk_seller_business_no'")에 의존</b>하며, 벤더·드라이버 교체나
 * 메시지 로케일 변경 시 판별이 false가 돼 409 대신 500으로 흐른다(fail-closed·오분류는 없음). Hibernate {@code ConstraintViolationException
 * .getConstraintName()}은 MariaDB 드라이버에서 null인 경우가 있어 채택하지 않았다(확인 시 교체 대상·D-187 §8).
 */
final class SellerConstraintViolations {

    /** V1 uk_seller_business_no — 메시지 판별 키. */
    static final String BUSINESS_NO_UNIQUE_KEY = "uk_seller_business_no";

    private SellerConstraintViolations() {
    }

    static boolean isBusinessNoDuplicate(DataIntegrityViolationException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String message = cause == null ? null : cause.getMessage();
        return message != null && message.contains(BUSINESS_NO_UNIQUE_KEY);
    }
}
