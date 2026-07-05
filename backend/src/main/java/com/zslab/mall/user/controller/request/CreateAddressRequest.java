package com.zslab.mall.user.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 배송지 생성 요청(Track 58 BL-4). 필수 4(recipientName·recipientPhone·zonecode·addressRoad)·나머지 옵션.
 * isDefault=true면 기존 기본 강등 후 지정(demote-then-set). 첫 주소는 서버가 기본으로 강제(요청값 무관).
 *
 * <p>@Size 상한은 user_address DDL @Column length와 동일 SoT.
 */
public record CreateAddressRequest(
        boolean isDefault,
        @Size(max = 50) String addressLabel, // SoT: address_label VARCHAR(50)
        @NotBlank @Size(max = 50) String recipientName, // SoT: recipient_name VARCHAR(50)
        @NotBlank @Size(max = 20) String recipientPhone, // SoT: recipient_phone VARCHAR(20)
        @NotBlank @Size(max = 10) String zonecode, // SoT: zonecode VARCHAR(10)
        @NotBlank @Size(max = 200) String addressRoad, // SoT: address_road VARCHAR(200)
        @Size(max = 200) String addressJibun, // SoT: address_jibun VARCHAR(200)
        @Size(max = 200) String addressDetail) { // SoT: address_detail VARCHAR(200)
}
