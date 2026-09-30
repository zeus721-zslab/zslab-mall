import type { InquiryAnsweredFilter, InquiryCategory } from '~/lib/constants/inquiry'

/**
 * 관리자 운영자 문의 API 타입(Track 106-4 · BE AdminInquiryResponse · AdminInquiryAnswerRequest 1:1). NON_NULL 직렬화로 생략될 수 있는 필드는
 * optional — 주문을 첨부하지 않았으면 주문 필드가, 미답변이면 답변 필드가, 탈퇴 비식별화 회원이면 buyerEmailMasked가 없다.
 */
export interface AdminInquiryListItem {
  inquiryId: string
  category: InquiryCategory
  content: string
  orderId?: string
  orderNo?: string
  buyerEmailMasked?: string
  answerContent?: string
  answeredAt?: string
  createdAt: string
}

export interface AdminInquiryListResponse {
  items: AdminInquiryListItem[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 목록 화면 상태(URL query 단일 소스). 정렬은 BE 고정(오래된 순). category null = 전체. */
export interface AdminInquiryListQuery {
  answered: InquiryAnsweredFilter
  category: InquiryCategory | null
  page: number
  size: number
}

export interface AdminInquiryApiParams {
  answered: InquiryAnsweredFilter
  category?: InquiryCategory
  page: number
  size: number
}
