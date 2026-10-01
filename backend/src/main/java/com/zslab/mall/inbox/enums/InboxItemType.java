package com.zslab.mall.inbox.enums;

import java.util.Set;

/**
 * 인박스 항목 유형(D-248 정의 확정표). DB {@code inbox_snooze.item_type} CHECK와 값이 같아야 한다(V44 · V46).
 * {@code targetKey}는 항목을 눌렀을 때 이동할 원천 리소스 종류이며, 경로는 역할·{@code ref}와 함께 FE가 정한다.
 */
public enum InboxItemType {
    CLAIM_REQUESTED("CLAIM", Set.of(InboxAudience.ADMIN)),
    CLAIM_FOLLOWUP("CLAIM", Set.of(InboxAudience.ADMIN)),
    LONG_SHIPPING("DELIVERY", Set.of(InboxAudience.ADMIN, InboxAudience.SELLER)),
    INQUIRY_UNANSWERED("INQUIRY", Set.of(InboxAudience.ADMIN)),
    SELLER_REVIEW("SELLER", Set.of(InboxAudience.ADMIN)),
    PRODUCT_APPROVAL("PRODUCT", Set.of(InboxAudience.ADMIN)),
    SETTLEMENT_CONFIRM("SETTLEMENT", Set.of(InboxAudience.ADMIN)),
    SETTLEMENT_PAYOUT("SETTLEMENT", Set.of(InboxAudience.ADMIN)),
    RECONCILIATION_OPEN("RECONCILIATION", Set.of(InboxAudience.ADMIN)),
    /** 셀러 지연(D-252) — 발송 대기·상품 Q&amp;A 미답변 중 기한을 넘긴 건이 있는 셀러 1행. ref는 sellerPublicId. */
    SELLER_DELAY("SELLER", Set.of(InboxAudience.ADMIN)),
    DELIVERY_READY("ORDER_ITEM", Set.of(InboxAudience.SELLER)),
    QUESTION_UNANSWERED("PRODUCT_QUESTION", Set.of(InboxAudience.SELLER)),
    LOW_STOCK("INVENTORY", Set.of(InboxAudience.SELLER));

    private final String targetKey;
    private final Set<InboxAudience> audiences;

    InboxItemType(String targetKey, Set<InboxAudience> audiences) {
        this.targetKey = targetKey;
        this.audiences = audiences;
    }

    public String targetKey() {
        return targetKey;
    }

    public boolean visibleTo(InboxAudience audience) {
        return audiences.contains(audience);
    }
}
