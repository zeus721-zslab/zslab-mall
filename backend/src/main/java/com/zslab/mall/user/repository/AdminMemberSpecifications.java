package com.zslab.mall.user.repository;

import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.user.controller.request.AdminMemberStatusFilter;
import com.zslab.mall.user.entity.User;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 회원 목록 필터(Track 84·{@code AdminOrderSpecifications} 패턴). 대상은 BUYER role 보유 회원이며 각 조건은 null이면 조건
 * 없음. 검색어는 이름·이메일·연락처 부분일치 OR이다(JPQL 파라미터 바인딩·SQL injection 위험 없음). soft-delete 회원은
 * {@code User @SQLRestriction}이 제외한다.
 */
public final class AdminMemberSpecifications {

    private AdminMemberSpecifications() {
    }

    /** BUYER role 매핑이 있는 회원만(user_role 서브쿼리). */
    public static Specification<User> buyerRole() {
        return (root, query, builder) -> {
            Subquery<Long> buyers = query.subquery(Long.class);
            Root<UserRole> userRole = buyers.from(UserRole.class);
            buyers.select(userRole.get("userId"))
                    .where(builder.equal(userRole.get("role").get("code"), RoleCode.BUYER));
            return root.get("id").in(buyers);
        };
    }

    /** ACTIVE = withdrawn_at IS NULL · WITHDRAWN = withdrawn_at IS NOT NULL. */
    public static Specification<User> status(AdminMemberStatusFilter status) {
        return (root, query, builder) -> {
            if (status == null) {
                return null;
            }
            return status == AdminMemberStatusFilter.ACTIVE
                    ? builder.isNull(root.get("withdrawnAt"))
                    : builder.isNotNull(root.get("withdrawnAt"));
        };
    }

    /** 검색: 이름 OR 이메일 OR 연락처 부분일치. */
    public static Specification<User> keyword(String likePattern) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            return builder.or(
                    builder.like(root.get("name"), likePattern, '\\'),
                    builder.like(root.get("email"), likePattern, '\\'),
                    builder.like(root.get("phone"), likePattern, '\\'));
        };
    }
}
