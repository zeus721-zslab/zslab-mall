package com.zslab.mall.user.controller.response;

/** 배송지 조회·생성·수정 응답(Track 58 BL-4). 식별자는 내부 id(소유권 스코프로 보호·public_id 미도입). */
public record AddressResponse(
        Long id,
        boolean isDefault,
        String addressLabel,
        String recipientName,
        String recipientPhone,
        String zonecode,
        String addressRoad,
        String addressJibun,
        String addressDetail) {
}
