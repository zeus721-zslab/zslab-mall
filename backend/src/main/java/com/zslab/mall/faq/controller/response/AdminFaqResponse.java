package com.zslab.mall.faq.controller.response;

import com.zslab.mall.common.serialization.KstOffsetSerializer;
import com.zslab.mall.faq.entity.Faq;
import com.zslab.mall.faq.enums.FaqCategory;
import java.time.LocalDateTime;
import tools.jackson.databind.annotation.JsonSerialize;

/** 관리자 FAQ 목록 항목(Track 106-3 · 숨김 포함). 정렬은 카테고리 선언 순서 → sortOrder → id. */
public record AdminFaqResponse(
        Long id,
        FaqCategory category,
        String question,
        String answer,
        int sortOrder,
        boolean visible,
        @JsonSerialize(using = KstOffsetSerializer.class)
        LocalDateTime updatedAt) {

    public static AdminFaqResponse from(Faq faq) {
        return new AdminFaqResponse(faq.getId(), faq.getCategory(), faq.getQuestion(), faq.getAnswer(), faq.getSortOrder(),
                faq.isVisible(), faq.getUpdatedAt());
    }
}
