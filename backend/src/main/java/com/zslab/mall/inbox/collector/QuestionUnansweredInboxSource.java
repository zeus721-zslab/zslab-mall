package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.enums.ProductQuestionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 셀러 · 상품 Q&amp;A 미답변 — 자기 상품의 공개(VISIBLE) 질문 중 answered_at 없음. 셀러 목록 기본 필터(ProductQuestionRepository.findForSeller
 * answered=false)와 같은 조건이며 삭제 상품·질문은 @SQLRestriction으로 빠진다.
 */
@Component
public class QuestionUnansweredInboxSource extends TimedInboxSource<ProductQuestion> {

    public QuestionUnansweredInboxSource(EntityManager entityManager) {
        super(entityManager, ProductQuestion.class, InboxItemType.QUESTION_UNANSWERED);
    }

    @Override
    protected InboxSelection<LocalDateTime> select(Root<ProductQuestion> root, CriteriaQuery<?> query,
            CriteriaBuilder builder, InboxViewer viewer, LocalDateTime now) {
        Root<Product> product = query.from(Product.class);
        return new InboxSelection<>(
                builder.and(builder.equal(product.get("id"), root.get("productId")),
                        builder.equal(product.get("sellerId"), viewer.sellerId()),
                        builder.equal(root.get("status"), ProductQuestionStatus.VISIBLE),
                        builder.isNull(root.get("answeredAt"))),
                root.get("createdAt"), root.get("publicId"), root.get("content"), product.get("name"));
    }
}
