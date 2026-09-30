package com.zslab.mall.demoseed.service;

import java.util.Random;

/**
 * 상품 하나의 데모 질문 부족분(D-244). 목표는 공개 질문(숨김·삭제 제외) {@link #PUBLIC_TARGET}건 이상 · 공개 미답변 1~{@link #UNANSWERED_MAX}건
 * (상품 id로 정한 결정적 목표)이며, 기존 미답변이 목표를 넘으면 그대로 둔다(기존 행은 바꾸지 않는다). 재호출하면 추가 0이 되도록 현재 개수만으로 계산한다.
 *
 * @param addUnanswered 새로 만들 미답변 질문 수
 * @param addAnswered   새로 만들고 셀러가 답변할 질문 수
 */
public record DemoQuestionQuota(int addUnanswered, int addAnswered) {

    public static final int PUBLIC_TARGET = 10;
    public static final int UNANSWERED_MAX = 5;

    /**
     * @param productId        결정적 미답변 목표의 난수 시드
     * @param publicCount      공개 질문 수(VISIBLE·미삭제)
     * @param publicUnanswered 공개 미답변 수
     */
    public static DemoQuestionQuota of(long productId, int publicCount, int publicUnanswered) {
        int unansweredTarget = unansweredTarget(productId);
        int shortage = Math.max(0, PUBLIC_TARGET - publicCount);
        int addUnanswered;
        if (shortage > 0) {
            addUnanswered = Math.min(Math.max(0, unansweredTarget - publicUnanswered), shortage);
        } else {
            // 이미 10건 이상이어도 공개 미답변이 하나도 없으면 1건만 더한다(미답변 1건 이상 조건)
            addUnanswered = publicUnanswered == 0 ? 1 : 0;
        }
        return new DemoQuestionQuota(addUnanswered, Math.max(0, shortage - addUnanswered));
    }

    /** 상품 id로 정한 미답변 목표(1~5). */
    public static int unansweredTarget(long productId) {
        return 1 + new Random(productId).nextInt(UNANSWERED_MAX);
    }

    public int total() {
        return addUnanswered + addAnswered;
    }
}
