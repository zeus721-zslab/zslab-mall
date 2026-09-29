package com.zslab.mall.review.controller.response;

/** 도움됐어요 토글 결과(Track 106-1). helped = 요청 후 요청자가 누른 상태, helpfulCount = 요청 후 리뷰의 누른 수. */
public record ReviewHelpfulResponse(boolean helped, int helpfulCount) {
}
