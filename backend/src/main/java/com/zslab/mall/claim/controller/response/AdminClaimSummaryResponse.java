package com.zslab.mall.claim.controller.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.zslab.mall.claim.enums.ClaimRejectReasonCode;
import com.zslab.mall.claim.enums.ClaimInspectionResult;
import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.refund.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 클레임 목록 행(Track 80 D-169). 유형 공용 컬럼만 담으며 반품·교환 전용 진행 표기(수거·교환 배송)는 Track 81·82에서 컬럼 추가한다.
 *
 * @param claimId          클레임 public_id(clm_)
 * @param orderId          주문 public_id(ord_)·상세 이동용
 * @param orderItemId      주문 품목 public_id(oit_)
 * @param orderNo          주문번호
 * @param buyerName        구매자 이름(탈퇴 비식별화 시 null)
 * @param buyerEmail       구매자 이메일(탈퇴 비식별화 시 null)
 * @param productName      상품명 스냅샷
 * @param optionLabel      옵션 라벨 스냅샷(없으면 null)
 * @param quantity         수량
 * @param amount           품목 금액(totalPrice·환불 기준액)
 * @param reasonCode       요청 사유 코드
 * @param reasonDetail     요청 상세 사유
 * @param rejectReasonCode 거부 사유 코드(거부 전 null)
 * @param rejectMemo       거부 메모(거부 전 null)
 * @param refundStatus     최신 환불 상태(환불 미생성 시 null)
 * @param availableActions 처리 가능 액션(REQUESTED: APPROVE·REJECT / RETURN APPROVED: 회수 송장 있고 미회수 CONFIRM_PICKUP·회수 후 미검수 INSPECT)
 * @param returnShipment   반품 회수 Delivery(구매자 등록·없으면 null·Track 81-A)
 * @param reshipment       검수 불합격 재발송 Delivery(없으면 null)
 * @param pickedUpAt       회수 확인 시각(RETURN·EXCHANGE)
 * @param inspectionResult 검수 결과(PASS|FAIL·미검수 null)
 * @param restock          검수 PASS 재입고 여부
 */
public record AdminClaimSummaryResponse(
        String claimId,
        ClaimType type,
        ClaimStatus status,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime requestedAt,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime processedAt,
        String orderId,
        String orderItemId,
        String orderNo,
        String buyerName,
        String buyerEmail,
        String productName,
        String optionLabel,
        int quantity,
        Long amount,
        String reasonCode,
        String reasonDetail,
        ClaimRejectReasonCode rejectReasonCode,
        String rejectMemo,
        RefundStatus refundStatus,
        List<String> availableActions,
        ReturnShipmentResponse returnShipment,
        ReturnShipmentResponse reshipment,
        @JsonSerialize(using = KstOffsetSerializer.class) LocalDateTime pickedUpAt,
        ClaimInspectionResult inspectionResult,
        Boolean restock) {
}
