package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inquiry.entity.Inquiry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** 관리자 · 1:1 문의 미답변(answered_at 없음·삭제 문의는 @SQLRestriction으로 제외) — 기준 createdAt. */
@Component
public class InquiryUnansweredInboxSource extends TimedInboxSource<Inquiry> {

    public InquiryUnansweredInboxSource(EntityManager entityManager) {
        super(entityManager, Inquiry.class, InboxItemType.INQUIRY_UNANSWERED);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<Inquiry> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        return new InboxSelection<>(builder.isNull(root.get("answeredAt")), root.get("createdAt"), root.get("publicId"),
                root.get("content"), root.get("category"));
    }
}
