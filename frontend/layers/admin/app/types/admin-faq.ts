/**
 * 관리자 FAQ 관리 타입(Track 106-3 · BE AdminFaqResponse · FaqWriteRequest · FaqReorderRequest 대응).
 */
import type { FaqCategory } from '~/lib/constants/faq'

export interface AdminFaqItem {
  id: number
  category: FaqCategory
  question: string
  answer: string
  sortOrder: number
  visible: boolean
  updatedAt: string
}

/** 등록·수정(PUT 전체 치환) 공통 본문. 정렬 위치는 서버가 정한다(카테고리 끝). */
export interface AdminFaqWriteRequest {
  category: FaqCategory
  question: string
  answer: string
  visible: boolean
}

/** 카테고리 안 일괄 정렬 — 해당 카테고리 전체 id(숨김 포함) · index = 노출 순서. */
export interface AdminFaqReorderRequest {
  category: FaqCategory
  faqIds: number[]
}

export interface AdminFaqCreatedResponse {
  id: number
}
