package com.zslab.mall.inbox.collector;

import com.zslab.mall.inbox.entity.InboxSnooze;
import com.zslab.mall.inbox.enums.InboxItemType;
import com.zslab.mall.inbox.enums.InboxTab;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Criteria로 조회하는 수집기의 공통 골격. 하위 클래스는 대기 조건·기준값·표시 문구 식({@link #select})과 기준값 해석만 정한다.
 *
 * <p>탭 경계는 기한이 아니라 기준값으로 옮겨 비교한다(기한 = 기준값의 단조 함수라 같은 결과) — 인덱스가 걸린 원천 컬럼을 그대로 쓰기 위해서다.
 * 보류 제외는 같은 쿼리의 NOT EXISTS라 상한·건수가 보류 반영 후 값이다. 모든 값은 Criteria 파라미터로 바인딩된다.
 *
 * @param <E> 수집 루트 엔티티
 * @param <T> 기준값 타입(시각 또는 날짜)
 */
public abstract class CriteriaInboxSource<E, T extends Comparable<? super T>> implements InboxSource {

    /** BIGINT 양수의 정규형(선행 0·부호·전각 숫자 없음·최대 18자리). */
    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,17}");

    private final EntityManager entityManager;
    private final Class<E> rootType;
    private final InboxItemType type;

    protected CriteriaInboxSource(EntityManager entityManager, Class<E> rootType, InboxItemType type) {
        this.entityManager = entityManager;
        this.rootType = rootType;
        this.type = type;
    }

    /** 대기 조건·기준값·식별자·표시 문구. 조회·건수 쿼리마다 호출되므로 부수효과 없이 식만 만든다. */
    protected abstract InboxSelection<T> select(Root<E> root, CriteriaQuery<?> query, CriteriaBuilder builder,
            InboxViewer viewer, LocalDateTime now);

    /** 기준값이 이 값보다 작으면 오늘 탭, 크거나 같으면 예정 탭. */
    protected abstract T tabCutoff(InboxWindow window);

    protected abstract LocalDateTime baseAtOf(T base);

    protected abstract LocalDateTime dueAtOf(T base);

    @Override
    public InboxItemType type() {
        return type;
    }

    @Override
    public InboxSlice collect(InboxViewer viewer, InboxWindow window) {
        List<InboxRow> rows = fetch(viewer, window);
        // 상한 미만이면 조회 행 수가 곧 전체 건수라 건수 쿼리를 생략한다.
        long total = rows.size() < window.limit() ? rows.size() : count(viewer, window);
        return new InboxSlice(rows, total);
    }

    @Override
    public long count(InboxViewer viewer, InboxWindow window) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<E> root = query.from(rootType);
        InboxSelection<T> selection = select(root, query, builder, viewer, window.now());
        query.select(builder.count(root))
                .where(selection.pending(), inTab(builder, selection.base(), window),
                        notSnoozed(query, builder, selection, viewer, window.now()));
        return entityManager.createQuery(query).getSingleResult();
    }

    @Override
    public boolean isPending(InboxViewer viewer, String ref) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<E> root = query.from(rootType);
        InboxSelection<T> selection = select(root, query, builder, viewer, LocalDateTime.now());
        Predicate sameItem;
        if (selection.numericId() == null) {
            sameItem = builder.equal(selection.ref(), ref);
        } else {
            // id 유형은 정규형 숫자 문자열만 받는다 — 저장된 item_ref가 곧 수집 ref(CAST(id AS CHAR))와 같고 보류 대조의 BIGINT 캐스트가 안전해진다.
            if (!CANONICAL_ID.matcher(ref).matches()) {
                return false;
            }
            sameItem = builder.equal(selection.numericId(), Long.valueOf(ref));
        }
        query.select(builder.count(root)).where(selection.pending(), sameItem);
        return entityManager.createQuery(query).getSingleResult() > 0;
    }

    private List<InboxRow> fetch(InboxViewer viewer, InboxWindow window) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createTupleQuery();
        Root<E> root = query.from(rootType);
        InboxSelection<T> selection = select(root, query, builder, viewer, window.now());
        query.select(builder.tuple(selection.ref(), selection.title(), selection.subtitle(), selection.base()))
                .where(selection.pending(), inTab(builder, selection.base(), window),
                        notSnoozed(query, builder, selection, viewer, window.now()))
                // 기준값 NULL(기한 없음)은 최종 정렬에서도 뒤라, 유형별 상한 슬롯을 기한 있는 행보다 먼저 차지하지 않게 한다.
                .orderBy(builder.asc(selection.base(), Nulls.LAST), builder.asc(selection.ref()));
        return entityManager.createQuery(query).setMaxResults(window.limit()).getResultList().stream()
                .map(tuple -> toRow(tuple, selection))
                .toList();
    }

    private InboxRow toRow(Tuple tuple, InboxSelection<T> selection) {
        T base = tuple.get(selection.base());
        LocalDateTime baseAt = base == null ? null : baseAtOf(base);
        LocalDateTime dueAt = base == null ? null : dueAtOf(base);
        return new InboxRow(type, tuple.get(selection.ref()), displayText(tuple.get(selection.title())),
                displayText(tuple.get(selection.subtitle())), baseAt, dueAt);
    }

    /** 기준값이 비어 있는 행(예: V29 이전 정산의 지급 예정일)은 기한 없음으로 오늘 탭에 둔다 — 대기 건이 두 탭 모두에서 사라지지 않게. */
    private Predicate inTab(CriteriaBuilder builder, Expression<T> base, InboxWindow window) {
        T cutoff = tabCutoff(window);
        if (window.tab() == InboxTab.TODAY) {
            return builder.or(builder.lessThan(base, cutoff), builder.isNull(base));
        }
        return builder.greaterThanOrEqualTo(base, cutoff);
    }

    private Predicate notSnoozed(CriteriaQuery<?> query, CriteriaBuilder builder, InboxSelection<T> selection,
            InboxViewer viewer, LocalDateTime now) {
        Subquery<Long> snoozed = query.subquery(Long.class);
        Root<InboxSnooze> snooze = snoozed.from(InboxSnooze.class);
        // id 유형의 item_ref는 보류 생성 시 대기 항목 대조(isPending)를 통과한 숫자 문자열뿐이라 BIGINT 캐스트가 안전하다.
        Predicate sameItem = selection.numericId() == null
                ? builder.equal(snooze.get("itemRef"), selection.ref())
                : builder.equal(snooze.<String>get("itemRef").cast(Long.class), selection.numericId());
        snoozed.select(snooze.get("id"))
                .where(builder.equal(snooze.get("ownerUserId"), viewer.userId()),
                        builder.equal(snooze.get("itemType"), type),
                        sameItem,
                        builder.greaterThan(snooze.<LocalDateTime>get("untilAt"), now));
        return builder.not(builder.exists(snoozed));
    }

    /** 표시 문구는 문자열 컬럼 또는 enum 코드다(라벨 변환은 FE 몫). */
    private static String displayText(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }
}
