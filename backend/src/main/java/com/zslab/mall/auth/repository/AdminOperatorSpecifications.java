package com.zslab.mall.auth.repository;

import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.user.entity.User;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/**
 * 운영자 목록 필터(Track 89-E·{@code AdminMemberSpecifications} 패턴). 모수는 ADMIN 계열 역할(SUPER_ADMIN·ADMIN_OPERATOR)
 * 매핑이 있는 회원이며 각 조건은 null이면 조건 없음. 검색어는 이름·이메일 부분일치 OR이다(JPQL 파라미터 바인딩·SQL injection
 * 위험 없음). soft-delete 회원은 {@code User @SQLRestriction}이 제외한다. 상태(withdrawn_at) 필터는
 * {@code AdminMemberSpecifications.status}를 재사용한다.
 */
public final class AdminOperatorSpecifications {

    private AdminOperatorSpecifications() {
    }

    /** codes 중 하나 이상의 역할 매핑이 있는 회원만(user_role 서브쿼리). */
    public static Specification<User> anyRole(Collection<RoleCode> codes) {
        return (root, query, builder) -> {
            Subquery<Long> holders = query.subquery(Long.class);
            Root<UserRole> userRole = holders.from(UserRole.class);
            holders.select(userRole.get("userId"))
                    .where(userRole.get("role").get("code").in(codes));
            return root.get("id").in(holders);
        };
    }

    /** 검색: 이름 OR 이메일 부분일치. */
    public static Specification<User> keyword(String likePattern) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            return builder.or(
                    builder.like(root.get("name"), likePattern, '\\'),
                    builder.like(root.get("email"), likePattern, '\\'));
        };
    }
}
