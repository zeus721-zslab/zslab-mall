package com.zslab.mall.faq.controller.response;

import com.zslab.mall.faq.entity.Faq;
import com.zslab.mall.faq.enums.FaqCategory;

/** 구매자 FAQ 항목(Track 106-3 · 전체 목록과 즉시 답이 같은 모양). 답은 말풍선에 그대로 보여 주므로 발췌하지 않는다. */
public record FaqResponse(
        Long id,
        FaqCategory category,
        String question,
        String answer) {

    public static FaqResponse from(Faq faq) {
        return new FaqResponse(faq.getId(), faq.getCategory(), faq.getQuestion(), faq.getAnswer());
    }
}
