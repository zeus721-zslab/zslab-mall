package com.zslab.mall.claim.controller.request;

import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimType;
import java.time.LocalDateTime;
import java.util.List;

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
        LocalDateTime requestedAt,
        List<String> attachmentIds,
        /** 교환 옵션 public id(var_·EXCHANGE 필수·Track 83 D-177). */
        String exchangeVariantPublicId) {

    /** 첨부 없는 요청(Track 81-B 이전 호출부 호환). */
    public ClaimRequestCommand(String orderItemPublicId, ClaimType claimType, ClaimReasonCode reasonCode, String reasonDetail,
            Long buyerId, LocalDateTime requestedAt) {
        this(orderItemPublicId, claimType, reasonCode, reasonDetail, buyerId, requestedAt, List.of(), null);
    }

    /** 첨부 있는 요청(Track 81-B 호출부 호환·교환 옵션 없음). */
    public ClaimRequestCommand(String orderItemPublicId, ClaimType claimType, ClaimReasonCode reasonCode, String reasonDetail,
            Long buyerId, LocalDateTime requestedAt, List<String> attachmentIds) {
        this(orderItemPublicId, claimType, reasonCode, reasonDetail, buyerId, requestedAt, attachmentIds, null);
    }
}
