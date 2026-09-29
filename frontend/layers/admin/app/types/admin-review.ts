import type { ReviewStatus } from '~/lib/constants/review'

/**
 * 관리자 리뷰 API 타입(Track 106-1 · BE AdminReviewResponse · AdminReviewStatusChangeRequest 1:1). NON_NULL 직렬화로 생략될 수 있는 필드는
 * optional — 상품이 삭제되면 productPublicId·productName이, 공개 리뷰면 hiddenReason이 없다.
 */
export interface AdminReviewListItem {
  reviewId: string
  productPublicId?: string
  productName?: string
  rating: number
  content: string
  optionLabel?: string
  status: ReviewStatus
  hiddenReason?: string
  helpfulCount: number
  /** 원본 사진 경로. 숨김 리뷰의 사진은 서빙이 404라(관리자 포함 · D-237 결정 4) 화면은 장수만 쓴다. */
  photoUrls: string[]
  createdAt: string
}

export interface AdminReviewListResponse {
  items: AdminReviewListItem[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 화면 상태(URL query 단일 소스). 정렬은 BE 고정(작성일 desc). */
export interface AdminReviewListQuery {
  status: ReviewStatus | null
  page: number
  size: number
}

export interface AdminReviewApiParams {
  status?: ReviewStatus
  page: number
  size: number
}

export interface AdminReviewStatusChangeRequest {
  status: ReviewStatus
  reason: string
}
