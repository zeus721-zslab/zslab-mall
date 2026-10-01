package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.settlement.enums.SettlementStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** 관리자 · 정산 지급 대기(CONFIRMED) — 기한 = 지급 예정일 당일. */
@Component
public class SettlementPayoutInboxSource extends SettlementInboxSource {

    public SettlementPayoutInboxSource(EntityManager entityManager) {
        super(entityManager, InboxItemType.SETTLEMENT_PAYOUT, SettlementStatus.CONFIRMED);
    }
}
