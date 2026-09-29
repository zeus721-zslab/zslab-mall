/**
 * 상품 리뷰 상수 단일 소스(Track 106-1 PR2 · 4층위 enum 잠금의 FE 층). BE enum(review/enums)과 V40 CHECK 값을 그대로 옮긴다.
 * 라벨 함수는 모르는 값이 와도 code를 그대로 보여 준다(BE가 값을 먼저 늘린 배포 순서 대비).
 */

/** 리뷰 공개 상태(BE ReviewStatus · V40 chk_review_status). */
export type ReviewStatus = 'VISIBLE' | 'HIDDEN'
export const REVIEW_STATUSES: ReviewStatus[] = ['VISIBLE', 'HIDDEN']
export const REVIEW_STATUS_LABELS: Record<ReviewStatus, string> = {
  VISIBLE: '공개',
  HIDDEN: '숨김',
}
export function reviewStatusLabel(status: string): string {
  return REVIEW_STATUS_LABELS[status as ReviewStatus] ?? status
}
export function isReviewStatus(value: unknown): value is ReviewStatus {
  return typeof value === 'string' && REVIEW_STATUSES.includes(value as ReviewStatus)
}

/** 주문 품목의 리뷰 작성 자격(BE ReviewEligibility · 응답 전용 파생값). */
export type ReviewEligibility = 'NOT_ELIGIBLE' | 'WRITABLE' | 'WRITTEN'

/** 공개 목록 정렬(BE ReviewSort · 조회 파라미터). */
export type ReviewSort = 'HELPFUL' | 'LATEST'
export const REVIEW_SORT_OPTIONS: { value: ReviewSort; label: string }[] = [
  { value: 'HELPFUL', label: '도움순' },
  { value: 'LATEST', label: '최신순' },
]

/** 키워드 묶음(BE ReviewKeywordGroup · V40 chk_review_keyword_group). 작성 폼·요약의 묶음 순서 = 배열 순서. */
export type ReviewKeywordGroup = 'DELIVERY' | 'PACKAGING' | 'QUALITY' | 'VALUE' | 'PRODUCT'
export const REVIEW_KEYWORD_GROUPS: ReviewKeywordGroup[] = ['PRODUCT', 'QUALITY', 'VALUE', 'DELIVERY', 'PACKAGING']
export const REVIEW_KEYWORD_GROUP_LABELS: Record<ReviewKeywordGroup, string> = {
  PRODUCT: '상품',
  QUALITY: '품질',
  VALUE: '가격',
  DELIVERY: '배송',
  PACKAGING: '포장',
}

/** 별점 범위(BE Review.MIN_RATING·MAX_RATING)와 점수별 문구(작성 폼 큰 별점). */
export const REVIEW_RATING_MIN = 1
export const REVIEW_RATING_MAX = 5
export const REVIEW_RATING_LABELS: Record<number, string> = {
  1: '별로예요',
  2: '그저 그래요',
  3: '괜찮아요',
  4: '좋아요',
  5: '최고예요',
}

/** 작성 입력 한도(BE ReviewCreateRequest · ReviewAttachmentService.MAX_PHOTOS_PER_REVIEW). */
export const REVIEW_CONTENT_MAX = 1000
export const REVIEW_KEYWORD_MAX = 10
export const REVIEW_PHOTO_MAX = 5

/** 공개 목록 한 번에 받는 수(BE 기본 size와 같다). */
export const REVIEW_PAGE_SIZE = 10
