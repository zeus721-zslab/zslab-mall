import type { ProductQuestionAnsweredFilter } from '~/lib/constants/product-question'

/**
 * 셀러 상품 질문 API 타입(Track 106-2 · BE SellerProductQuestionResponse · ProductQuestionAnswerRequest 1:1). NON_NULL 직렬화로 생략될 수 있는
 * 필드는 optional — 미답변이면 answerContent·answeredAt이, 상품이 삭제되면 상품 필드가 없다. 작성자 정보는 없다.
 */
export interface SellerProductQuestionItem {
  questionId: string
  productPublicId?: string
  productName?: string
  content: string
  answerContent?: string
  answeredAt?: string
  createdAt: string
}

export interface SellerProductQuestionListResponse {
  items: SellerProductQuestionItem[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 화면 상태 = URL query 단일 소스. 정렬은 BE 고정(작성일 오래된 순 · 미답변부터 처리). */
export interface SellerProductQuestionListQuery {
  answered: ProductQuestionAnsweredFilter
  page: number
  size: number
}

export interface SellerProductQuestionApiParams {
  answered: ProductQuestionAnsweredFilter
  page: number
  size: number
}
