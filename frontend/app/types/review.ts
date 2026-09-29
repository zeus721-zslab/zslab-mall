/**
 * 상품 리뷰 API 타입(Track 106-1 · BE review/controller/response 대응). 응답은 전역 NON_NULL이라 null 필드는 키가 빠진다 —
 * 그런 필드는 선택(?)으로 둔다(averageRating·summaryText·helpedByMe·hiddenReason·optionLabel 등).
 */
import type { ReviewEligibility, ReviewKeywordGroup, ReviewSort, ReviewStatus } from '~/lib/constants/review'

/**
 * 주문 품목의 리뷰 상태(BE OrderItemReviewResponse). reviewId는 WRITTEN이면서 삭제되지 않은 리뷰에만 온다.
 * hidden은 WRITTEN일 때만 온다(관리자 숨김 여부 · 사유는 작성자 단건 조회에만 있다).
 */
export interface OrderItemReview {
  status: ReviewEligibility
  reviewId?: string
  hidden?: boolean
}

/** 리뷰 키워드(BE KeywordResponse). */
export interface ReviewKeyword {
  code: string
  label: string
}

/** 작성 폼 키워드 선택지(BE ReviewKeywordOptionResponse · PR2). */
export interface ReviewKeywordOption extends ReviewKeyword {
  groupCode: ReviewKeywordGroup
  sortOrder: number
}

/** 공개 리뷰 사진(BE ReviewPhotoResponse). 작은 이미지는 thumbnailUrl = url. */
export interface ReviewPhoto {
  url: string
  thumbnailUrl: string
}

/** 공개 리뷰 목록 항목(BE ReviewItemResponse). helpedByMe·writtenByMe는 비로그인이면 없다. */
export interface ReviewItem {
  reviewId: string
  rating: number
  content: string
  optionLabel?: string
  keywords: ReviewKeyword[]
  photos: ReviewPhoto[]
  helpfulCount: number
  helpedByMe?: boolean
  writtenByMe?: boolean
  createdAt: string
}

/** 리뷰 요약(BE ReviewSummaryResponse). 리뷰 0건이면 averageRating이, 요약이 없으면 summaryText가 없다. */
export interface ReviewSummary {
  reviewCount: number
  averageRating?: number
  ratingDistribution: { rating: number; count: number }[]
  keywords: (ReviewKeyword & { count: number })[]
  recentPhotos: (ReviewPhoto & { reviewId: string })[]
  summaryText?: string
}

/** 작성자 본인 리뷰(BE ReviewOwnerResponse). 숨김이면 hiddenReason이 있고 photosPublic=false(사진은 작성자에게도 404). */
export interface ReviewOwner {
  reviewId: string
  rating: number
  content: string
  optionLabel?: string
  keywords: ReviewKeyword[]
  photos: (ReviewPhoto & { attachmentId: string })[]
  status: ReviewStatus
  hiddenReason?: string
  photosPublic: boolean
  createdAt: string
}

/** 작성·수정 요청 본문(BE ReviewCreateRequest · ReviewUpdateRequest). 수정은 orderItemId 없이 전체 교체. */
export interface ReviewWriteBody {
  rating: number
  keywordCodes: string[]
  content: string
  attachmentIds: string[]
}

/** 도움됐어요 토글 결과(BE ReviewHelpfulResponse). */
export interface ReviewHelpfulResult {
  helped: boolean
  helpfulCount: number
}

/** 사진 업로드 응답 항목(BE ReviewAttachmentUploadResponse.Item). 성공 항목만 attachmentId를 가진다. */
export interface ReviewPhotoUploadItem {
  fileName?: string
  success: boolean
  attachmentId?: string
  url?: string
  thumbnailUrl?: string
  code?: string
  message?: string
}

export interface ReviewPhotoUploadResponse {
  results: ReviewPhotoUploadItem[]
  successCount: number
  failureCount: number
}

/**
 * 작성 폼에 붙은 사진(FE 전용). previewUrl은 새로 올린 사진이면 로컬 object URL(연결 전 서버 URL은 404 — D-237 결정 4),
 * 수정 화면에서 불러온 기존 사진이면 서버 썸네일이다. 요청에는 attachmentId만 배열 순서대로 보낸다.
 */
export interface ReviewFormPhoto {
  attachmentId: string
  previewUrl: string
  name: string
}

/** 공개 목록 조회 조건. 값이 없으면 보내지 않는다. */
export interface ReviewListFilter {
  photoOnly: boolean
  keyword: string | null
  option: string | null
  sort: ReviewSort
}
