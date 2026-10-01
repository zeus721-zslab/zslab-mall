package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.enums.InboxTab;
import com.zslab.mall.inbox.repository.InboxSnoozeRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.repository.SellerRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 관리자 · 셀러 지연(D-252) — 발송 대기·상품 Q&amp;A 미답변 중 기한을 넘긴 건이 1건 이상인 셀러 1행. ref = sellerPublicId · title = 상호 ·
 * subtitle = 유형별 초과 건수 · 기한 = 가장 오래된 초과 건의 기한이라 항상 지나 있어 오늘 탭에만 들어간다.
 *
 * <p>행이 원천 1행이 아니라 집계라 Criteria 골격({@link CriteriaInboxSource})을 쓰지 않는다. 집계 2문장 + 셀러 조회 1문장 + 보류 조회 1문장으로
 * 셀러 수와 무관하며, 관리자 본인 보류 제외는 같은 규칙(소유자·유형·ref·만료 전)을 쿼리 밖에서 대조한다.
 */
@Component
public class SellerDelayInboxSource implements InboxSource {

    private static final Comparator<InboxRow> DUE_ORDER = Comparator
            .comparing(InboxRow::dueAt)
            .thenComparing(InboxRow::ref);

    private final SellerDelayCounter sellerDelayCounter;
    private final SellerRepository sellerRepository;
    private final InboxSnoozeRepository inboxSnoozeRepository;

    public SellerDelayInboxSource(SellerDelayCounter sellerDelayCounter, SellerRepository sellerRepository,
            InboxSnoozeRepository inboxSnoozeRepository) {
        this.sellerDelayCounter = sellerDelayCounter;
        this.sellerRepository = sellerRepository;
        this.inboxSnoozeRepository = inboxSnoozeRepository;
    }

    @Override
    public InboxItemType type() {
        return InboxItemType.SELLER_DELAY;
    }

    @Override
    public InboxSlice collect(InboxViewer viewer, InboxWindow window) {
        List<InboxRow> rows = rows(viewer, window);
        return new InboxSlice(rows.stream().limit(window.limit()).toList(), rows.size());
    }

    @Override
    public long count(InboxViewer viewer, InboxWindow window) {
        return rows(viewer, window).size();
    }

    @Override
    public boolean isPending(InboxViewer viewer, String ref) {
        return sellerRepository.findByPublicId(ref)
                .map(seller -> sellerDelayCounter.countOf(seller.getId(), LocalDateTime.now()).hasDelay())
                .orElse(false);
    }

    private List<InboxRow> rows(InboxViewer viewer, InboxWindow window) {
        if (window.tab() != InboxTab.TODAY) {
            return List.of();
        }
        Map<Long, SellerDelay> delays = sellerDelayCounter.countAll(window.now());
        if (delays.isEmpty()) {
            return List.of();
        }
        Set<String> snoozed = new HashSet<>(
                inboxSnoozeRepository.findActiveItemRefs(viewer.userId(), InboxItemType.SELLER_DELAY, window.now()));
        List<InboxRow> rows = new ArrayList<>();
        for (Seller seller : sellerRepository.findAllById(delays.keySet())) {
            if (snoozed.contains(seller.getPublicId())) {
                continue;
            }
            SellerDelay delay = delays.get(seller.getId());
            rows.add(new InboxRow(InboxItemType.SELLER_DELAY, seller.getPublicId(), seller.getCompanyName(), subtitle(delay),
                    delay.oldestBaseAt(), delay.oldestDueAt()));
        }
        rows.sort(DUE_ORDER);
        return rows;
    }

    /** "발송 대기 N건 · Q&amp;A 미답변 N건"(0건 유형은 뺀다). */
    private static String subtitle(SellerDelay delay) {
        List<String> parts = new ArrayList<>();
        if (delay.deliveryReadyCount() > 0) {
            parts.add("발송 대기 " + delay.deliveryReadyCount() + "건");
        }
        if (delay.questionUnansweredCount() > 0) {
            parts.add("Q&A 미답변 " + delay.questionUnansweredCount() + "건");
        }
        return String.join(" · ", parts);
    }
}
