package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.settlement.enums.SettlementStatus;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

/** 관리자 · 정산 확정 대기(PENDING) — 기한 = 지급 예정일 3일 전. */
@Component
public class SettlementConfirmInboxSource extends SettlementInboxSource {

    public SettlementConfirmInboxSource(EntityManager entityManager) {
        super(entityManager, InboxItemType.SETTLEMENT_CONFIRM, SettlementStatus.PENDING);
    }
}
