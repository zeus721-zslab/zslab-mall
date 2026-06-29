package com.zslab.mall.claim.controller.request;

import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;

/**
 * 클레임 요청 입력(Service 계층 Command·ClaimService.request 단일 진입).
 *
 * <p>orderItemPublicId(oit_)는 Service 진입점에서 {@code OrderItemRepository.findByPublicId}로 BIGINT id를 해소한다
 * (D-64·D-65 정합·publicId 운반·Service 해소). 형식 검증은 DTO(@Valid)·도메인 검증(CANCEL 한정·소유권·CLM-5)은
 * ClaimService가 담당한다.
 */
public record ClaimRequestCommand(
        String orderItemPublicId,
        ClaimType claimType,
        ClaimReasonCode reasonCode,
        String reasonDetail,
        Long buyerId,
        LocalDateTime requestedAt) {
}
