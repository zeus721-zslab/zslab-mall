import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type {
  AdminProductQuestionApiParams,
  AdminProductQuestionListQuery,
} from '#layers/admin/app/types/admin-product-question'
import {
  ADMIN_PRODUCT_QUESTION_EXCERPT_LENGTH,
  ADMIN_PRODUCT_QUESTION_PAGE_SIZES,
  ADMIN_PRODUCT_QUESTION_STATUS_TONE,
  DEFAULT_ADMIN_PRODUCT_QUESTION_PAGE_SIZE,
} from '#layers/admin/app/lib/constants/admin-product-question'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { isProductQuestionStatus, type ProductQuestionStatus } from '~/lib/constants/product-question'

/**
 * 관리자 상품 질문 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑과 표시 함수(Track 106-2 · admin-review-query·view 복제). 기본값과 같은 항목은
 * URL에서 빼고, 잘못된 값(모르는 status·음수 page·허용 밖 size)은 기본값으로 정규화한다. 필터는 BE가 받는 상태 하나뿐이다.
 */

export const DEFAULT_ADMIN_PRODUCT_QUESTION_QUERY: AdminProductQuestionListQuery = {
  status: null,
  page: 0,
  size: DEFAULT_ADMIN_PRODUCT_QUESTION_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

export function parseAdminProductQuestionQuery(query: LocationQuery): AdminProductQuestionListQuery {
  const status = first(query.status)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    status: isProductQuestionStatus(status) ? status : null,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_PRODUCT_QUESTION_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_PRODUCT_QUESTION_PAGE_SIZE,
  }
}

export function toAdminProductQuestionRouteQuery(state: AdminProductQuestionListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.status) query.status = state.status
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_PRODUCT_QUESTION_PAGE_SIZE) query.size = String(state.size)
  return query
}

export function toAdminProductQuestionApiParams(state: AdminProductQuestionListQuery): AdminProductQuestionApiParams {
  const params: AdminProductQuestionApiParams = { page: state.page, size: state.size }
  if (state.status) params.status = state.status
  return params
}

export function hasActiveProductQuestionFilters(state: AdminProductQuestionListQuery): boolean {
  return state.status !== null
}

export function productQuestionStatusChipClass(status: ProductQuestionStatus): string {
  return semanticChipClass(ADMIN_PRODUCT_QUESTION_STATUS_TONE[status])
}

/** 질문 발췌(줄바꿈은 공백으로 · 길면 말줄임). */
export function productQuestionExcerpt(content: string): string {
  const flat = content.replace(/\s+/g, ' ').trim()
  return flat.length > ADMIN_PRODUCT_QUESTION_EXCERPT_LENGTH ? `${flat.slice(0, ADMIN_PRODUCT_QUESTION_EXCERPT_LENGTH)}…` : flat
}

/** 현재 상태에서 바꿀 목표 상태(공개 ↔ 숨김). */
export function productQuestionTransitionTarget(status: ProductQuestionStatus): ProductQuestionStatus {
  return status === 'VISIBLE' ? 'HIDDEN' : 'VISIBLE'
}
