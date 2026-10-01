package com.zslab.mall.claim.service;

import com.zslab.mall.claim.enums.ClaimReasonCode;
import com.zslab.mall.claim.enums.ClaimSuggestionRule;
import com.zslab.mall.claim.enums.ClaimType;
import com.zslab.mall.order.enums.OrderItemStatus;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 코드 규칙표(D-250 §2) — 위에서부터 첫 일치. */
@Component
public class RuleClaimSuggestionPolicy implements ClaimSuggestionPolicy {

    private static final Set<OrderItemStatus> UNSHIPPED_STATUSES = Set.of(OrderItemStatus.PAID, OrderItemStatus.PREPARING);
    private static final Set<String> EVIDENCE_REASONS = Set.of(
            ClaimReasonCode.PRODUCT_DEFECT.name(), ClaimReasonCode.WRONG_PRODUCT.name());

    @Override
    public ClaimSuggestionRule evaluate(ClaimSuggestionInput input) {
        if (input.type() == ClaimType.EXCHANGE && !exchangeStockSufficient(input)) {
            return ClaimSuggestionRule.EXCHANGE_STOCK_SHORT;
        }
        if (input.type() == ClaimType.CANCEL && UNSHIPPED_STATUSES.contains(input.previousItemStatus())) {
            return ClaimSuggestionRule.UNSHIPPED_CANCEL;
        }
        if (input.type().isPickupBased()) {
            if (EVIDENCE_REASONS.contains(input.reasonCode())) {
                return input.attachmentCount() > 0
                        ? ClaimSuggestionRule.DEFECT_WITH_EVIDENCE
                        : ClaimSuggestionRule.DEFECT_WITHOUT_EVIDENCE;
            }
            if (ClaimReasonCode.BUYER_CHANGED_MIND.name().equals(input.reasonCode())) {
                return ClaimSuggestionRule.CHANGE_OF_MIND;
            }
        }
        return ClaimSuggestionRule.NO_MATCH;
    }

    private static boolean exchangeStockSufficient(ClaimSuggestionInput input) {
        return Boolean.TRUE.equals(input.exchangeOptionOnSale())
                && input.exchangeAvailableStock() != null
                && input.exchangeAvailableStock() >= input.quantity();
    }
}
