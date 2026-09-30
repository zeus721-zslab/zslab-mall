import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminInquiryApiParams, AdminInquiryListQuery } from '#layers/admin/app/types/admin-inquiry'
import { isInquiryAnsweredFilter, isInquiryCategory } from '~/lib/constants/inquiry'

/**
 * 관리자 문의 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑과 표시 함수(Track 106-4 · admin-product-question-query 선례). 기본값(미답변 ·
 * 전체 카테고리 · 첫 페이지 · 기본 크기)은 URL에서 빼고, 잘못된 값은 기본값으로 정규화한다.
 */

/** 페이지 크기 선택지(BE 최대 50) · 목록 본문 발췌 길이. */
export const ADMIN_INQUIRY_PAGE_SIZES: number[] = [10, 20, 50]
export const DEFAULT_ADMIN_INQUIRY_PAGE_SIZE = 20
export const ADMIN_INQUIRY_EXCERPT_LENGTH = 60

export const DEFAULT_ADMIN_INQUIRY_QUERY: AdminInquiryListQuery = {
  answered: 'UNANSWERED',
  category: null,
  page: 0,
  size: DEFAULT_ADMIN_INQUIRY_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

export function parseAdminInquiryQuery(query: LocationQuery): AdminInquiryListQuery {
  const answered = first(query.answered)
  const category = first(query.category)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    answered: isInquiryAnsweredFilter(answered) ? answered : DEFAULT_ADMIN_INQUIRY_QUERY.answered,
    category: isInquiryCategory(category) ? category : null,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_INQUIRY_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_INQUIRY_PAGE_SIZE,
  }
}

export function toAdminInquiryRouteQuery(state: AdminInquiryListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.answered !== DEFAULT_ADMIN_INQUIRY_QUERY.answered) query.answered = state.answered
  if (state.category) query.category = state.category
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_INQUIRY_PAGE_SIZE) query.size = String(state.size)
  return query
}

export function toAdminInquiryApiParams(state: AdminInquiryListQuery): AdminInquiryApiParams {
  const params: AdminInquiryApiParams = { answered: state.answered, page: state.page, size: state.size }
  if (state.category) params.category = state.category
  return params
}

/** 기본값과 다른 필터가 있는지(빈 목록에서 "필터 초기화" 버튼 노출). */
export function hasActiveInquiryFilters(state: AdminInquiryListQuery): boolean {
  return state.answered !== DEFAULT_ADMIN_INQUIRY_QUERY.answered || state.category !== null
}

/** 본문 발췌(줄바꿈은 공백으로 · 길면 말줄임). */
export function inquiryExcerpt(content: string): string {
  const flat = content.replace(/\s+/g, ' ').trim()
  return flat.length > ADMIN_INQUIRY_EXCERPT_LENGTH ? `${flat.slice(0, ADMIN_INQUIRY_EXCERPT_LENGTH)}…` : flat
}
