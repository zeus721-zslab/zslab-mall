import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type {
  SellerProductQuestionApiParams,
  SellerProductQuestionListQuery,
} from '#layers/seller/app/types/seller-product-question'
import { isProductQuestionAnsweredFilter } from '~/lib/constants/product-question'

/**
 * 셀러 상품 질문 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(Track 106-2 · seller-claim-query 복제). 기본값(미답변 · 첫 페이지 · 기본 크기)은
 * URL에서 빼고, 잘못된 값(모르는 answered·음수 page·허용 밖 size)은 기본값으로 정규화한다.
 */

/** 페이지 크기 선택지(BE 최대 50). */
export const SELLER_PRODUCT_QUESTION_PAGE_SIZES: number[] = [10, 20, 50]
export const DEFAULT_SELLER_PRODUCT_QUESTION_PAGE_SIZE = 20

export const DEFAULT_SELLER_PRODUCT_QUESTION_QUERY: SellerProductQuestionListQuery = {
  answered: 'UNANSWERED',
  page: 0,
  size: DEFAULT_SELLER_PRODUCT_QUESTION_PAGE_SIZE,
}

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

export function parseSellerProductQuestionQuery(query: LocationQuery): SellerProductQuestionListQuery {
  const answered = first(query.answered)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    answered: isProductQuestionAnsweredFilter(answered) ? answered : DEFAULT_SELLER_PRODUCT_QUESTION_QUERY.answered,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: SELLER_PRODUCT_QUESTION_PAGE_SIZES.includes(size) ? size : DEFAULT_SELLER_PRODUCT_QUESTION_PAGE_SIZE,
  }
}

export function toSellerProductQuestionRouteQuery(state: SellerProductQuestionListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.answered !== DEFAULT_SELLER_PRODUCT_QUESTION_QUERY.answered) query.answered = state.answered
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_SELLER_PRODUCT_QUESTION_PAGE_SIZE) query.size = String(state.size)
  return query
}

export function toSellerProductQuestionApiParams(state: SellerProductQuestionListQuery): SellerProductQuestionApiParams {
  return { answered: state.answered, page: state.page, size: state.size }
}
