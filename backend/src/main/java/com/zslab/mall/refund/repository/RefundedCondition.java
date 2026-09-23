package com.zslab.mall.refund.repository;

import com.zslab.mall.refund.entity.Refund;
import com.zslab.mall.refund.enums.RefundStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/**
 * "환불된 금액"으로 세는 환불 행 조건 단일 소스(Track 104-3a). 완료(COMPLETED)·진행 중(PENDING)이거나, 실패(FAILED)로 처리했지만 PG가
 * 성공을 통지한 행이다. 품목 상한 합계·멱등 게이트(JPQL {@link #JPQL}), 관리자 필요 액션 필터(Criteria {@link #predicate}), 목록 행의
 * 재개시 판정(메모리 {@link #matches})이 같은 조건을 쓴다 — 조건을 바꾸면 이 파일의 세 표현을 함께 고친다.
 */
public final class RefundedCondition {

    /**
     * JPQL 조건(환불 별칭 {@code r}). enum은 FQN 리터럴이라 바인딩 파라미터가 없다(ClaimRepository 선례) — SQL injection 위험 없음.
     */
    public static final String JPQL = "(r.status IN (com.zslab.mall.refund.enums.RefundStatus.PENDING, "
            + "com.zslab.mall.refund.enums.RefundStatus.COMPLETED) "
            + "OR (r.status = com.zslab.mall.refund.enums.RefundStatus.FAILED AND r.pgRefundSucceededAt IS NOT NULL))";

    private RefundedCondition() {
    }

    /** Criteria 조건 — {@link #JPQL}과 같다. */
    public static Predicate predicate(Root<Refund> refund, CriteriaBuilder builder) {
        return builder.or(
                refund.get("status").in(RefundStatus.PENDING, RefundStatus.COMPLETED),
                builder.and(
                        builder.equal(refund.get("status"), RefundStatus.FAILED),
                        builder.isNotNull(refund.get("pgRefundSucceededAt"))));
    }

    /** 메모리 판정 — {@link #JPQL}과 같다. */
    public static boolean matches(Refund refund) {
        return switch (refund.getStatus()) {
            case PENDING, COMPLETED -> true;
            case FAILED -> refund.getPgRefundSucceededAt() != null;
        };
    }
}
