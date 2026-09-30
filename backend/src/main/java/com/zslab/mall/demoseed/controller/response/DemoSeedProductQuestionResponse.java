package com.zslab.mall.demoseed.controller.response;

import java.util.List;

/**
 * 데모 상품 Q&A 적재 결과(D-244). dryRun이면 계획만 담고 created 수는 0이다.
 *
 * @param dryRun               계획만 산출했는지
 * @param targetSellerCount    대상 셀러 수(데모 상호 · ACTIVE · 소유 구성원 있음)
 * @param targetProductCount   대상 상품 수(대상 셀러의 SALE 상품)
 * @param createdQuestionCount 이번 호출에서 만든 질문 수(dryRun 0)
 * @param createdAnswerCount   이번 호출에서 만든 답변 수(dryRun 0)
 * @param products             상품별 현황·추가분
 * @param excludedSellers      제외된 데모 상호 셀러와 사유
 * @param failedProducts       실패해 롤백된 상품과 사유
 */
public record DemoSeedProductQuestionResponse(
        boolean dryRun,
        int targetSellerCount,
        int targetProductCount,
        int createdQuestionCount,
        int createdAnswerCount,
        List<ProductPlan> products,
        List<ExcludedSeller> excludedSellers,
        List<FailedProduct> failedProducts) {

    /**
     * @param publicCount      호출 전 공개 질문 수
     * @param publicUnanswered 호출 전 공개 미답변 수
     * @param addQuestionCount 추가(할) 질문 수
     * @param addAnswerCount   그중 셀러가 답변(할) 수
     */
    public record ProductPlan(String productPublicId, String productName, String companyName, int publicCount, int publicUnanswered,
            int addQuestionCount, int addAnswerCount) {
    }

    public record ExcludedSeller(String sellerPublicId, String companyName, String reason) {
    }

    public record FailedProduct(String productPublicId, String reason) {
    }
}
