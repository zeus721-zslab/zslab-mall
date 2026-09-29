package com.zslab.mall.review.event;

/**
 * 상품의 공개 리뷰 구성이 바뀌었을 수 있음(Track 106-1·작성·수정·삭제·숨김·숨김 해제). 소비처는 한 줄 요약 재계산 하나이며 payload는 상품 id만
 * 담는다 — 소비 시점에 원천을 다시 읽는다(D-30 재조회 선례).
 */
public record ReviewChangedEvent(Long productId) {
}
