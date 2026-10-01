package com.zslab.mall.inbox.service;

import com.zslab.mall.claim.service.ClaimSuggestionResult;
import com.zslab.mall.claim.service.ClaimSuggestionService;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.inbox.collector.InboxRow;
import com.zslab.mall.inbox.collector.InboxSlice;
import com.zslab.mall.inbox.collector.InboxSource;
import com.zslab.mall.inbox.collector.InboxViewer;
import com.zslab.mall.inbox.collector.InboxWindow;
import com.zslab.mall.inbox.controller.response.InboxItemResponse;
import com.zslab.mall.inbox.controller.response.InboxResponse;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.enums.InboxTab;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영 인박스 수집(D-248). 보는 사람의 유형별 수집기를 돌려 기한 순으로 합친다. 읽기 전용이며 원천 상태를 바꾸지 않는다.
 *
 * <p>유형별로 최대 {@link #MAX_ITEMS}건만 읽고(기준값 오름차순 = 그 유형 안의 기한 순) 합친 뒤 다시 {@link #MAX_ITEMS}건으로 자르므로,
 * 잘린 항목은 어느 유형에서든 기한이 가장 늦은 쪽이다.
 */
@Service
@Transactional(readOnly = true)
public class InboxQueryService {

    public static final int MAX_ITEMS = 200;

    private static final Comparator<InboxRow> DUE_ORDER = Comparator
            .comparing(InboxRow::dueAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(InboxRow::baseAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(InboxRow::type)
            .thenComparing(InboxRow::ref);

    private final Map<InboxItemType, InboxSource> sources;
    private final ClaimSuggestionService claimSuggestionService;

    public InboxQueryService(List<InboxSource> sources, ClaimSuggestionService claimSuggestionService) {
        this.claimSuggestionService = claimSuggestionService;
        this.sources = new EnumMap<>(InboxItemType.class);
        for (InboxSource source : sources) {
            this.sources.put(source.type(), source);
        }
    }

    /**
     * @param typeFilter 한 유형만 보기(null이면 전체). 건수는 필터와 무관하게 모든 유형을 센다.
     * @throws MalformedRequestException 보는 사람의 인박스에 없는 유형으로 필터한 경우(400)
     */
    public InboxResponse collect(InboxViewer viewer, InboxTab tab, InboxItemType typeFilter) {
        if (typeFilter != null && !typeFilter.visibleTo(viewer.audience())) {
            throw new MalformedRequestException("이 인박스에 없는 유형입니다: " + typeFilter);
        }
        LocalDateTime now = LocalDateTime.now();
        InboxWindow window = new InboxWindow(tab, now, now.toLocalDate(), MAX_ITEMS);

        List<InboxRow> rows = new ArrayList<>();
        List<InboxResponse.TypeCount> counts = new ArrayList<>();
        long listedTotal = 0;
        for (InboxSource source : visibleSources(viewer)) {
            long total;
            if (typeFilter == null || typeFilter == source.type()) {
                InboxSlice slice = source.collect(viewer, window);
                rows.addAll(slice.rows());
                total = slice.total();
                listedTotal += total;
            } else {
                total = source.count(viewer, window);
            }
            counts.add(new InboxResponse.TypeCount(source.type(), total));
        }

        List<InboxRow> listed = rows.stream()
                .sorted(DUE_ORDER)
                .limit(MAX_ITEMS)
                .toList();
        Map<String, ClaimSuggestionResult> claimSuggestions = claimSuggestions(listed);
        List<InboxItemResponse> items = listed.stream()
                .map(row -> toResponse(row, now, claimSuggestions))
                .toList();
        return new InboxResponse(items, counts, listedTotal > items.size());
    }

    private static InboxItemResponse toResponse(InboxRow row, LocalDateTime now, Map<String, ClaimSuggestionResult> claimSuggestions) {
        ClaimSuggestionResult claim = row.type() == InboxItemType.CLAIM_REQUESTED ? claimSuggestions.get(row.ref()) : null;
        return claim == null
                ? InboxItemResponse.of(row, now, null, null)
                : InboxItemResponse.of(row, now, claim.input().type(), claim.suggestion());
    }

    /** 클레임 접수 행의 유형·제안을 행 목록 단위로 한 번에 계산한다(D-250 · 행마다 조회하지 않는다). */
    private Map<String, ClaimSuggestionResult> claimSuggestions(List<InboxRow> listed) {
        List<String> claimRefs = listed.stream()
                .filter(row -> row.type() == InboxItemType.CLAIM_REQUESTED)
                .map(InboxRow::ref)
                .toList();
        return claimSuggestionService.suggestRequestedByPublicIds(claimRefs);
    }

    /** 보류 대상 검증용 — 보는 사람의 인박스에 그 유형이 있을 때만 수집기를 돌려준다. */
    Optional<InboxSource> sourceFor(InboxViewer viewer, InboxItemType type) {
        if (!type.visibleTo(viewer.audience())) {
            return Optional.empty();
        }
        return Optional.ofNullable(sources.get(type));
    }

    private List<InboxSource> visibleSources(InboxViewer viewer) {
        return sources.values().stream()
                .filter(source -> source.type().visibleTo(viewer.audience()))
                .toList();
    }
}
