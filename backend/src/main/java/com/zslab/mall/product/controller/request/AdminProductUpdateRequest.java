package com.zslab.mall.product.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * 관리자 상품 기본정보 수정 요청(Track 76·전체 치환 PUT). 셀러·상태는 바꾸지 않는다(셀러는 order_item.seller_id 스냅샷과 충돌·
 * 상태는 sale-status/approve 경로). 판매 시작 ≥ 종료는 Service 도메인 검증(400 MALFORMED_REQUEST).
 */
public record AdminProductUpdateRequest(
        @NotNull Long categoryId,
        @NotBlank @Size(max = 200) String name,
        String description,
        @NotNull @PositiveOrZero Long basePrice,
        @PositiveOrZero Long supplyPrice,
        @Size(max = 2048) String thumbnailUrl,
        OffsetDateTime saleStartAt,
        OffsetDateTime saleEndAt) {
}
