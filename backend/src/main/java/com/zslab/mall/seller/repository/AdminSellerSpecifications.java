package com.zslab.mall.seller.repository;

import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import org.springframework.data.jpa.domain.Specification;

/**
 * 관리자 셀러 목록 필터(Track 89-D·{@code AdminMemberSpecifications} 패턴). 각 조건은 null이면 조건 없음. 검색어는
 * 상호·사업자번호·담당자 이메일 부분일치 OR이다(Criteria 파라미터 바인딩·SQL injection 위험 없음). soft-delete 셀러는
 * {@code Seller @SQLRestriction}이 제외한다.
 */
public final class AdminSellerSpecifications {

    private AdminSellerSpecifications() {
    }

    public static Specification<Seller> status(SellerStatus status) {
        return (root, query, builder) -> status == null ? null : builder.equal(root.get("status"), status);
    }

    /** 검색: 상호 OR 사업자번호 OR 담당자 이메일 부분일치(호출부가 escape·%감싸기까지 마친 LIKE 패턴). */
    public static Specification<Seller> keyword(String likePattern) {
        return (root, query, builder) -> {
            if (likePattern == null) {
                return null;
            }
            return builder.or(
                    builder.like(root.get("companyName"), likePattern, '\\'),
                    builder.like(root.get("businessNo"), likePattern, '\\'),
                    builder.like(root.get("contactEmail"), likePattern, '\\'));
        };
    }
}
