package com.zslab.mall.review.controller.response;

/** 리뷰 사진(Track 106-1). 소형 이미지는 썸네일이 없어 thumbnailUrl = url이다. */
public record ReviewPhotoResponse(String url, String thumbnailUrl) {
}
