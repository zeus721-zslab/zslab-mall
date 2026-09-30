import type { ProductQuestionStatus } from '~/lib/constants/product-question'

/**
 * 관리자 상품 질문 API 타입(Track 106-2 · BE AdminProductQuestionResponse · AdminProductQuestionStatusChangeRequest 1:1). NON_NULL 직렬화로
 * 생략될 수 있는 필드는 optional — 상품이 삭제되면 상품 필드가, 미답변이면 답변 필드가, 공개 질문이면 hiddenReason이 없다.
 */
export interface AdminProductQuestionListItem {
  questionId: string
  productPublicId?: string
  productName?: string
  content: string
  answerContent?: string
  answeredAt?: string
  status: ProductQuestionStatus
  hiddenReason?: string
  createdAt: string
}

export interface AdminProductQuestionListResponse {
  items: AdminProductQuestionListItem[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 화면 상태(URL query 단일 소스). 정렬은 BE 고정(작성일 desc). */
export interface AdminProductQuestionListQuery {
  status: ProductQuestionStatus | null
  page: number
  size: number
}

export interface AdminProductQuestionApiParams {
  status?: ProductQuestionStatus
  page: number
  size: number
}

export interface AdminProductQuestionStatusChangeRequest {
  status: ProductQuestionStatus
  reason: string
}
