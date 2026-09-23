package com.zslab.mall.reconciliation.service;

import com.zslab.mall.claim.enums.ClaimStatus;
import com.zslab.mall.delivery.enums.DeliveryStatus;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.reconciliation.enums.ReconciliationIssueType;
import java.util.List;

/**
 * 점검 스케줄러가 저장된 행으로 찾는 불일치 패턴(Track 104-2 D-216). 기록 시 detail.reason에 이름이 그대로 들어가며(FE 라벨 대상),
 * 중복 키는 {@code dedupePrefix + 대상 id}다. U14(교환 배송 완료인데 배송·클레임 행 부재)는 FK상 저장된 행으로 도달할 수 없어 제외했다.
 */
public enum ReconciliationCheckPattern {

    /** U5: 환불·클레임 없이 결제만 취소됨. */
    PAYMENT_CANCELLED_WITHOUT_REFUND(ReconciliationIssueType.PAYMENT_CANCELLED_WITHOUT_REFUND, "payment:", null, null, List.of()),
    /** U6: 전액 환불 완료인데 결제 PAID. */
    FULL_REFUND_PAYMENT_NOT_CANCELLED(ReconciliationIssueType.FULL_REFUND_PAYMENT_NOT_CANCELLED, "payment:", null, null, List.of()),
    /** U7: 구매확정 품목이 있는데 전액 환불(환불 완료 시점 기록과 같은 중복 키). */
    FULL_REFUND_WITH_CONFIRMED_ITEM(ReconciliationIssueType.FULL_REFUND_WITH_CONFIRMED_ITEM, "payment:", null, null, List.of()),
    /** U10: 클레임 완료인데 품목이 요청 상태. */
    CLAIM_COMPLETED_ITEM_NOT_TRANSITIONED(ReconciliationIssueType.ITEM_STATE_DRIFT, "claim-completed:", ClaimStatus.COMPLETED, null,
            List.of(OrderItemStatus.CANCEL_REQUESTED, OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.EXCHANGE_REQUESTED)),
    /** U11: 클레임 거부인데 품목이 요청 상태(원복 안 됨). */
    CLAIM_REJECTED_ITEM_NOT_RESTORED(ReconciliationIssueType.ITEM_STATE_DRIFT, "claim-rejected:", ClaimStatus.REJECTED, null,
            List.of(OrderItemStatus.CANCEL_REQUESTED, OrderItemStatus.RETURN_REQUESTED, OrderItemStatus.EXCHANGE_REQUESTED)),
    /** U12: 원 발송이 배송중인데 품목이 발송 전 상태. */
    SHIPPED_ITEM_NOT_TRANSITIONED(ReconciliationIssueType.ITEM_STATE_DRIFT, "delivery-shipping:", null, DeliveryStatus.SHIPPING,
            List.of(OrderItemStatus.PAID, OrderItemStatus.PREPARING)),
    /** U13: 원 발송이 배송완료인데 품목이 배송완료 전 상태. */
    DELIVERED_ITEM_NOT_TRANSITIONED(ReconciliationIssueType.ITEM_STATE_DRIFT, "delivery-delivered:", null, DeliveryStatus.DELIVERED,
            List.of(OrderItemStatus.PAID, OrderItemStatus.PREPARING, OrderItemStatus.SHIPPING));

    private final ReconciliationIssueType issueType;
    private final String dedupePrefix;
    private final ClaimStatus claimStatus;
    private final DeliveryStatus deliveryStatus;
    private final List<OrderItemStatus> staleItemStatuses;

    ReconciliationCheckPattern(ReconciliationIssueType issueType, String dedupePrefix, ClaimStatus claimStatus,
            DeliveryStatus deliveryStatus, List<OrderItemStatus> staleItemStatuses) {
        this.issueType = issueType;
        this.dedupePrefix = dedupePrefix;
        this.claimStatus = claimStatus;
        this.deliveryStatus = deliveryStatus;
        this.staleItemStatuses = staleItemStatuses;
    }

    public ReconciliationIssueType issueType() {
        return issueType;
    }

    public String dedupePrefix() {
        return dedupePrefix;
    }

    /** 클레임 패턴의 클레임 상태(그 외 null). */
    public ClaimStatus claimStatus() {
        return claimStatus;
    }

    /** 배송 패턴의 배송 상태(그 외 null). */
    public DeliveryStatus deliveryStatus() {
        return deliveryStatus;
    }

    /** 클레임·배송 패턴에서 "아직 이 상태면 어긋남"인 품목 상태 이름 목록(네이티브 IN 바인딩용). */
    public List<String> staleItemStatusNames() {
        return staleItemStatuses.stream().map(Enum::name).toList();
    }
}
