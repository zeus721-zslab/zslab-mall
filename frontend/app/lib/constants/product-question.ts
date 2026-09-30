/**
 * 상품 Q&A 상수 단일 소스(Track 106-2 · 4층위 enum 잠금의 FE 층). BE enum(productquestion/enums)과 V41 CHECK 값을 그대로 옮긴다.
 * 라벨 함수는 모르는 값이 와도 code를 그대로 보여 준다(BE가 값을 먼저 늘린 배포 순서 대비).
 */

/** 질문 공개 상태(BE ProductQuestionStatus · V41 chk_product_question_status). */
export type ProductQuestionStatus = 'VISIBLE' | 'HIDDEN'
export const PRODUCT_QUESTION_STATUSES: ProductQuestionStatus[] = ['VISIBLE', 'HIDDEN']
export const PRODUCT_QUESTION_STATUS_LABELS: Record<ProductQuestionStatus, string> = {
  VISIBLE: '공개',
  HIDDEN: '숨김',
}
export function productQuestionStatusLabel(status: string): string {
  return PRODUCT_QUESTION_STATUS_LABELS[status as ProductQuestionStatus] ?? status
}
export function isProductQuestionStatus(value: unknown): value is ProductQuestionStatus {
  return typeof value === 'string' && PRODUCT_QUESTION_STATUSES.includes(value as ProductQuestionStatus)
}

/** 즉시 답 출처(BE ProductQuestionSuggestionType · 응답 전용). 카드 유형 라벨. */
export type ProductQuestionSuggestionType = 'QNA' | 'REVIEW' | 'PRODUCT'
export const PRODUCT_QUESTION_SUGGESTION_LABELS: Record<ProductQuestionSuggestionType, string> = {
  QNA: 'Q&A',
  REVIEW: '리뷰',
  PRODUCT: '상품 설명',
}
export function suggestionTypeLabel(type: string): string {
  return PRODUCT_QUESTION_SUGGESTION_LABELS[type as ProductQuestionSuggestionType] ?? type
}

/** 셀러 목록 답변 여부 필터(BE ProductQuestionAnsweredFilter · 기본 UNANSWERED). */
export type ProductQuestionAnsweredFilter = 'ALL' | 'UNANSWERED' | 'ANSWERED'
export const PRODUCT_QUESTION_ANSWERED_FILTERS: ProductQuestionAnsweredFilter[] = ['UNANSWERED', 'ANSWERED', 'ALL']
export const PRODUCT_QUESTION_ANSWERED_LABELS: Record<ProductQuestionAnsweredFilter, string> = {
  UNANSWERED: '미답변',
  ANSWERED: '답변완료',
  ALL: '전체',
}
export function isProductQuestionAnsweredFilter(value: unknown): value is ProductQuestionAnsweredFilter {
  return typeof value === 'string' && PRODUCT_QUESTION_ANSWERED_FILTERS.includes(value as ProductQuestionAnsweredFilter)
}

/** 입력 한도(BE ProductQuestion.MIN/MAX_CONTENT_LENGTH · MAX_ANSWER_LENGTH · 관리자 사유 @Size(max = 200)). 질문은 trim 후 길이다. */
export const PRODUCT_QUESTION_CONTENT_MIN = 5
export const PRODUCT_QUESTION_CONTENT_MAX = 500
export const PRODUCT_QUESTION_ANSWER_MAX = 1000
export const PRODUCT_QUESTION_REASON_MAX = 200

/** 즉시 답: trim 후 이 길이 이상일 때만 부른다(BE q 2~100자 · 넘는 입력은 앞 100자만 보낸다) · 입력 뒤 대기 시간. */
export const PRODUCT_QUESTION_SUGGEST_MIN = 2
export const PRODUCT_QUESTION_SUGGEST_MAX = 100
export const PRODUCT_QUESTION_SUGGEST_DEBOUNCE_MS = 300

/** 공개 목록·내 질문 한 번에 받는 수(BE 기본 size와 같다). */
export const PRODUCT_QUESTION_PAGE_SIZE = 10
