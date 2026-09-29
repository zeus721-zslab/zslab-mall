import type { ReviewSort } from '~/lib/constants/review'
import type { ReviewItem, ReviewListFilter, ReviewSummary } from '~/types/review'

/** 요약의 막대 한 줄(별점 분포 · 키워드 집계). ratio = 0~1 · valueText = 막대 오른쪽 문구. */
export interface ReviewBar {
  key: string
  label: string
  ratio: number
  valueText: string
  strong: boolean
}

/**
 * 상품 상세 리뷰 섹션 vm(Track 106-1 PR2 · ProductDetailPageVm.reviews). 요약·첫 페이지는 SSR, 더보기·필터·도움됐어요는 브라우저에서 한다.
 * 필터·정렬이 바뀌면 첫 페이지부터 다시 받는다. 도움됐어요는 비로그인이면 로그인으로 보내고, 본인 리뷰는 항목의 writtenByMe로 처음부터 잠근다
 * (방어용으로 BE 422를 받으면 ownReviewIds에 넣어 그 뒤로도 잠근다).
 */
export interface ProductReviewsVm {
  summary: ReviewSummary | null
  ratingBars: ReviewBar[]
  keywordBars: ReviewBar[]
  recentPhotoThumbnails: string[]
  recentPhotoUrls: string[]
  items: ReviewItem[]
  totalCount: number
  pending: boolean
  failed: boolean
  retry: () => Promise<void>
  hasNext: boolean
  loadingMore: boolean
  loadMore: () => Promise<void>
  filter: ReviewListFilter
  isFiltered: boolean
  optionChoices: string[]
  setPhotoOnly: (photoOnly: boolean) => void
  setKeyword: (code: string | null) => void
  setOption: (label: string | null) => void
  setSort: (sort: ReviewSort) => void
  resetFilter: () => void
  toggleHelpful: (item: ReviewItem) => Promise<void>
  helpfulPendingId: string | null
  ownReviewIds: string[]
  helpfulNotice: { reviewId: string; text: string } | null
}
