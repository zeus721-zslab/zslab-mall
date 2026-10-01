package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/** 관리자 · 셀러 입점 심사(PENDING) — 기준 createdAt(신청 시각 전용 필드 없음). */
@Component
public class SellerReviewInboxSource extends TimedInboxSource<Seller> {

    public SellerReviewInboxSource(EntityManager entityManager) {
        super(entityManager, Seller.class, InboxItemType.SELLER_REVIEW);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<Seller> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now) {
        return new InboxSelection<>(builder.equal(root.get("status"), SellerStatus.PENDING), root.get("createdAt"),
                root.get("publicId"), root.get("companyName"), builder.nullLiteral(String.class));
    }
}
