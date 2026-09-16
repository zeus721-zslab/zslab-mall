import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { AdminOrderApiParams, AdminOrderListQuery } from '#layers/admin/app/types/admin-order'
import { ORDER_STATUS_LABELS, type OrderStatusCode } from '~/lib/constants/order'
import {
  ADMIN_DELIVERY_STATUS_LABEL,
  ADMIN_ORDER_KEYWORD_MAX,
  ADMIN_ORDER_PAGE_SIZES,
  ADMIN_ORDER_SORT_OPTIONS,
  ADMIN_PAYMENT_STATUS_LABEL,
  DEFAULT_ADMIN_ORDER_PAGE_SIZE,
  DEFAULT_ADMIN_ORDER_SORT,
  type AdminDeliveryStatus,
  type AdminOrderSort,
  type AdminPaymentStatus,
} from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 주문 목록 상태 ↔ URL query ↔ BE 파라미터 순수 매핑(FE-27·admin-product-query 패턴). URL이 단일 소스라 새로고침·뒤로가기에도
 * 필터가 유지된다. 기본값과 같은 항목은 URL에서 생략하고, 잘못된 값(미지의 status·sort·날짜 형식·음수 page)은 기본값으로 정규화한다.
 * 기간은 URL·화면에서 yyyy-MM-dd로 다루고 BE(LocalDateTime ISO)에는 from=T00:00:00·to=T23:59:59를 부착해 보낸다.
 */

export const DEFAULT_ADMIN_ORDER_QUERY: AdminOrderListQuery = {
  keyword: '',
  status: null,
  paymentStatus: null,
  deliveryStatus: null,
  from: null,
  to: null,
  sort: DEFAULT_ADMIN_ORDER_SORT,
  page: 0,
  size: DEFAULT_ADMIN_ORDER_PAGE_SIZE,
}

const DATE_ONLY_PATTERN = /^\d{4}-\d{2}-\d{2}$/
const DAY_START_TIME = 'T00:00:00'
const DAY_END_TIME = 'T23:59:59'

function first(value: LocationQuery[string] | undefined): string | null {
  const single = Array.isArray(value) ? value[0] : value
  return typeof single === 'string' && single !== '' ? single : null
}

function isOrderStatus(value: string): value is OrderStatusCode {
  return value in ORDER_STATUS_LABELS
}

function isPaymentStatus(value: string): value is AdminPaymentStatus {
  return value in ADMIN_PAYMENT_STATUS_LABEL
}

function isDeliveryStatus(value: string): value is AdminDeliveryStatus {
  return value in ADMIN_DELIVERY_STATUS_LABEL
}

function isSort(value: string): value is AdminOrderSort {
  return ADMIN_ORDER_SORT_OPTIONS.some((option) => option.value === value)
}

/** yyyy-MM-dd 형식만 통과(네이티브 date 입력값·URL 오염값 방어). */
export function normalizeDateOnly(value: string | null): string | null {
  return value !== null && DATE_ONLY_PATTERN.test(value) ? value : null
}

/** 기간 시작(yyyy-MM-dd) → BE LocalDateTime ISO(그날 00:00:00). */
export function toPeriodStart(date: string): string {
  return `${date}${DAY_START_TIME}`
}

/** 기간 종료(yyyy-MM-dd) → BE LocalDateTime ISO(그날 23:59:59·종료일 포함). */
export function toPeriodEnd(date: string): string {
  return `${date}${DAY_END_TIME}`
}

/** route.query → 화면 상태. 검색어는 BE 한도(50자)로 잘라 400을 예방한다. */
export function parseAdminOrderQuery(query: LocationQuery): AdminOrderListQuery {
  const status = first(query.status)
  const paymentStatus = first(query.paymentStatus)
  const deliveryStatus = first(query.deliveryStatus)
  const sort = first(query.sort)
  const page = Number(first(query.page))
  const size = Number(first(query.size))
  return {
    keyword: (first(query.keyword)?.trim() ?? '').slice(0, ADMIN_ORDER_KEYWORD_MAX),
    status: status && isOrderStatus(status) ? status : null,
    paymentStatus: paymentStatus && isPaymentStatus(paymentStatus) ? paymentStatus : null,
    deliveryStatus: deliveryStatus && isDeliveryStatus(deliveryStatus) ? deliveryStatus : null,
    from: normalizeDateOnly(first(query.from)),
    to: normalizeDateOnly(first(query.to)),
    sort: sort && isSort(sort) ? sort : DEFAULT_ADMIN_ORDER_SORT,
    page: Number.isInteger(page) && page > 0 ? page : 0,
    size: ADMIN_ORDER_PAGE_SIZES.includes(size) ? size : DEFAULT_ADMIN_ORDER_PAGE_SIZE,
  }
}

/** 화면 상태 → router.replace용 query(기본값 항목 생략). */
export function toAdminOrderRouteQuery(state: AdminOrderListQuery): LocationQueryRaw {
  const query: LocationQueryRaw = {}
  if (state.keyword.trim() !== '') query.keyword = state.keyword.trim()
  if (state.status) query.status = state.status
  if (state.paymentStatus) query.paymentStatus = state.paymentStatus
  if (state.deliveryStatus) query.deliveryStatus = state.deliveryStatus
  if (state.from) query.from = state.from
  if (state.to) query.to = state.to
  if (state.sort !== DEFAULT_ADMIN_ORDER_SORT) query.sort = state.sort
  if (state.page > 0) query.page = String(state.page)
  if (state.size !== DEFAULT_ADMIN_ORDER_PAGE_SIZE) query.size = String(state.size)
  return query
}

/** 화면 상태 → BE GET /admin/orders 파라미터(null·빈 값 제외·page/size/sort는 항상 포함·기간은 시각 부착). */
export function toAdminOrderApiParams(state: AdminOrderListQuery): AdminOrderApiParams {
  const params: AdminOrderApiParams = { sort: state.sort, page: state.page, size: state.size }
  const keyword = state.keyword.trim()
  if (keyword !== '') params.keyword = keyword
  if (state.status) params.status = state.status
  if (state.paymentStatus) params.paymentStatus = state.paymentStatus
  if (state.deliveryStatus) params.deliveryStatus = state.deliveryStatus
  if (state.from) params.from = toPeriodStart(state.from)
  if (state.to) params.to = toPeriodEnd(state.to)
  return params
}

/** 필터(검색·상태 3종·기간)가 하나라도 걸려 있는지 — 빈 상태 문구("결과 없음" vs "주문 없음") 분기용. */
export function hasActiveFilters(state: AdminOrderListQuery): boolean {
  return (
    state.keyword.trim() !== '' ||
    state.status !== null ||
    state.paymentStatus !== null ||
    state.deliveryStatus !== null ||
    state.from !== null ||
    state.to !== null
  )
}

/** 기간 역전(from > to) 여부 — BE 400(MALFORMED_REQUEST) 전에 필터 카드가 안내한다. 둘 다 있을 때만 판정. */
export function isPeriodInverted(state: Pick<AdminOrderListQuery, 'from' | 'to'>): boolean {
  return state.from !== null && state.to !== null && state.from > state.to
}
