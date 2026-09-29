import type { OrderItemReview } from '~/types/review'

/**
 * 주문 품목 → 리뷰 작성·수정 경로(Track 106-1 PR2 · 순수 함수). 클레임 요청 폼처럼 표시용 값(상품명·옵션)은 query로 넘기고,
 * 키워드 선택지 조회에 필요한 상품 public id(prd_)도 함께 넘긴다(작성자 리뷰 응답에는 상품 식별자가 없다).
 * 주문 목록 품목(OrderSummaryItem)과 상세 품목(OrderItem) 모두 받도록 필요한 필드만 본다.
 */
export interface ReviewLinkItem {
  orderItemId: string
  productId?: string | null
  productName?: string | null
  optionLabel?: string | null
  review?: OrderItemReview
}

function productQuery(item: ReviewLinkItem): string {
  const option = item.optionLabel ? `&option=${encodeURIComponent(item.optionLabel)}` : ''
  return `product=${item.productId ?? ''}&name=${encodeURIComponent(item.productName ?? '')}${option}`
}

/** 작성할 수 있는 품목인가 — 구매확정(WRITABLE)이고 상품이 남아 있을 때(키워드 선택지를 상품 기준으로 받는다). */
export function canWriteReview(item: ReviewLinkItem): boolean {
  return item.review?.status === 'WRITABLE' && Boolean(item.productId)
}

/** 작성 페이지 경로. rating(1~5)을 주면 폼의 큰 별점을 채운 채 연다. */
export function reviewWritePath(item: ReviewLinkItem, rating?: number): string {
  const ratingQuery = rating === undefined ? '' : `&rating=${rating}`
  return `/reviews/new?orderItem=${item.orderItemId}&${productQuery(item)}${ratingQuery}`
}

/** 수정 페이지 경로. 작성했고 삭제되지 않은 리뷰(reviewId 있음)만 — 삭제한 리뷰는 재작성·수정이 없다(D-237 결정 1). */
export function reviewEditPath(item: ReviewLinkItem): string | null {
  const reviewId = item.review?.status === 'WRITTEN' ? item.review.reviewId : undefined
  if (!reviewId || !item.productId) return null
  return `/reviews/${reviewId}/edit?${productQuery(item)}`
}
