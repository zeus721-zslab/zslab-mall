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

/**
 * 등록 다이얼로그 미리 채우기(D-253 · FE-107 · 문의 답변의 FAQ 후보). category null = 운영자가 직접 골라야 한다(문의 OTHER). notices는 절삭·미선택
 * 안내 문구다.
 */
export interface AdminFaqPrefill {
  category: FaqCategory | null
  question: string
  answer: string
  notices: string[]
}

/** 카테고리 안 일괄 정렬 — 해당 카테고리 전체 id(숨김 포함) · index = 노출 순서. */
export interface AdminFaqReorderRequest {
  category: FaqCategory
  faqIds: number[]
}

export interface AdminFaqCreatedResponse {
  id: number
}
