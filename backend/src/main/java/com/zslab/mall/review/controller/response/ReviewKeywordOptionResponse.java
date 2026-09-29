package com.zslab.mall.review.controller.response;

import com.zslab.mall.review.enums.ReviewKeywordGroup;

/** 작성 폼 키워드 선택지(Track 106-1 PR2). groupCode는 폼의 묶음 기준, sortOrder는 표시 순서. */
public record ReviewKeywordOptionResponse(String code, String label, ReviewKeywordGroup groupCode, int sortOrder) {
}
