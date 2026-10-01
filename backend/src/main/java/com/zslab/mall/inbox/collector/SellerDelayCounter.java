package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.policy.InboxDeadlinePolicy;
import com.zslab.mall.order.entity.OrderItem;
import com.zslab.mall.product.entity.Product;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 셀러 지연 집계(D-252 정의 확정표). 발송 대기·상품 Q&amp;A 미답변 중 기한을 넘긴 건을 셀러별로 센다. 대기 조건은 셀러 인박스 수집기의 식을 그대로
 * 쓰고({@link DeliveryReadyInboxSource#pending}·{@link QuestionUnansweredInboxSource#pending}), 셀러 보류와 무관하다.
 *
 * <p>"기한 &lt; 현재 시각"은 "기준 시각 &lt; 현재 시각 − 기한 간격"으로 옮겨 비교한다(같은 판정 · 기한과 같은 시각은 초과 아님). 유형별 GROUP BY 1문장씩이라
 * 셀러 수와 무관하게 2문장이다. 모든 값은 Criteria 파라미터로 바인딩된다.
 */
@Component
public class SellerDelayCounter {

    private final EntityManager entityManager;

    public SellerDelayCounter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 초과가 1건 이상인 셀러만 담는다(키 = 셀러 id). */
    public Map<Long, SellerDelay> countAll(LocalDateTime now) {
        return count(now, null);
    }

    /** 셀러 1곳(초과 없으면 0건 값). */
    public SellerDelay countOf(Long sellerId, LocalDateTime now) {
        return count(now, sellerId).getOrDefault(sellerId, SellerDelay.NONE);
    }

    private Map<Long, SellerDelay> count(LocalDateTime now, Long sellerId) {
        Map<Long, TypeCount> deliveryReady = countDeliveryReady(now, sellerId);
        Map<Long, TypeCount> questionUnanswered = countQuestionUnanswered(now, sellerId);
        Map<Long, SellerDelay> delays = new HashMap<>();
        for (Long id : union(deliveryReady, questionUnanswered)) {
            TypeCount delivery = deliveryReady.getOrDefault(id, TypeCount.ZERO);
            TypeCount question = questionUnanswered.getOrDefault(id, TypeCount.ZERO);
            delays.put(id, new SellerDelay(delivery.count(), delivery.oldestBase(), question.count(), question.oldestBase()));
        }
        return delays;
    }

    private Map<Long, TypeCount> countDeliveryReady(LocalDateTime now, Long sellerId) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<OrderItem> root = query.from(OrderItem.class);
        return group(query, builder, root, root.get("sellerId"), DeliveryReadyInboxSource.base(root),
                DeliveryReadyInboxSource.pending(root, builder), InboxItemType.DELIVERY_READY, now, sellerId);
    }

    private Map<Long, TypeCount> countQuestionUnanswered(LocalDateTime now, Long sellerId) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<ProductQuestion> root = query.from(ProductQuestion.class);
        Root<Product> product = query.from(Product.class);
        return group(query, builder, root, product.get("sellerId"), QuestionUnansweredInboxSource.base(root),
                QuestionUnansweredInboxSource.pending(root, product, builder), InboxItemType.QUESTION_UNANSWERED, now, sellerId);
    }

    /** 셀러별 초과 건수(건수 단위 = 루트 1행)·가장 이른 기준 시각. sellerId가 있으면 그 셀러만 거른다. */
    private Map<Long, TypeCount> group(CriteriaQuery<Tuple> query, CriteriaBuilder builder, Root<?> unit, Expression<Long> seller,
            Expression<LocalDateTime> base, Predicate pending, InboxItemType type, LocalDateTime now, Long sellerId) {
        LocalDateTime overdueBaseBefore = now.minus(InboxDeadlinePolicy.timedDeadline(type));
        List<Predicate> where = new ArrayList<>(List.of(pending, builder.lessThan(base, overdueBaseBefore)));
        if (sellerId != null) {
            where.add(builder.equal(seller, sellerId));
        }
        query.select(builder.tuple(seller, builder.count(unit), builder.least(base)))
                .where(where.toArray(Predicate[]::new))
                .groupBy(seller);
        Map<Long, TypeCount> counts = new HashMap<>();
        for (Tuple tuple : entityManager.createQuery(query).getResultList()) {
            counts.put(tuple.get(0, Long.class), new TypeCount(tuple.get(1, Long.class), tuple.get(2, LocalDateTime.class)));
        }
        return counts;
    }

    private static List<Long> union(Map<Long, TypeCount> first, Map<Long, TypeCount> second) {
        List<Long> ids = new ArrayList<>(first.keySet());
        second.keySet().stream().filter(id -> !first.containsKey(id)).forEach(ids::add);
        return ids;
    }

    private record TypeCount(long count, LocalDateTime oldestBase) {

        static final TypeCount ZERO = new TypeCount(0, null);
    }
}
