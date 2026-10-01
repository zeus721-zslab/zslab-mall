package com.zslab.mall.inbox.collector;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * 수집 Criteria 조각. 대기 조건·기준값·식별자·표시 문구 식을 한 번에 넘긴다(조회·건수 쿼리마다 새로 만든다).
 *
 * @param pending   대기 조건(셀러 소유 범위 포함)
 * @param base      기준값(정렬·탭 경계)
 * @param subtitle  부제(없으면 null 리터럴)
 * @param numericId id를 식별자로 쓰는 유형의 원천 id(publicId 유형은 null). 이 유형은 보류 대조를 숫자로 한다 — {@code CAST(id AS CHAR)}는
 *                  연결 collation(MariaDB 11 기본 uca1400)을 따라 {@code inbox_snooze.item_ref}(unicode_ci)와 비교하면
 *                  "Illegal mix of collations" 오류가 난다.
 */
public record InboxSelection<T>(Predicate pending, Expression<T> base, Expression<String> ref, Expression<?> title,
        Expression<?> subtitle, Expression<Long> numericId) {

    public InboxSelection(Predicate pending, Expression<T> base, Expression<String> ref, Expression<?> title,
            Expression<?> subtitle) {
        this(pending, base, ref, title, subtitle, null);
    }
}
