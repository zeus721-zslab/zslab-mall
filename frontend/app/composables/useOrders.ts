import type { OrderDetail, OrderSummary, PagedResponse } from '~/types/order'

/** 목록 기본 페이지 크기(BE BuyerOrderController list 기본 size=20 정합). */
const DEFAULT_PAGE_SIZE = 20

/**
 * API base 이원화(useCheckout·useProductList와 동일): SSR은 내부 직결(apiInternalBase), 브라우저는 동일 Origin 상대경로.
 */
function resolveApiBase(): string {
  const config = useRuntimeConfig()
  return import.meta.server ? `${config.apiInternalBase}/api` : config.public.apiBase || '/api'
}

/**
 * 구매자 주문 목록 조회(GET /api/v1/orders?page&size). BUYER 전용 API라 Authorization: Bearer를 주입한다
 * (permitAll인 useProductDetail을 복제하지 않는다). page는 Ref로 받아 변경 시 useFetch가 SSR 페이로드와 함께 재조회한다.
 * 401 등 인증 실패는 error로 노출해 소비 페이지가 /login으로 유도한다.
 */
export function useOrderList(page: Ref<number>, size: number = DEFAULT_PAGE_SIZE) {
  const auth = useAuthStore()
  return useFetch<PagedResponse<OrderSummary>>('/v1/orders', {
    key: 'order-list',
    baseURL: resolveApiBase(),
    query: { page, size },
    headers: { Authorization: `Bearer ${auth.token}` },
  })
}

/**
 * 구매자 주문 단건 조회(GET /api/v1/orders/{orderPublicId}). BUYER 전용이라 Bearer 주입. 미존재·타인 주문은 BE가 404
 * (존재 은닉)를 반환하며 useFetch가 error로 노출한다. key는 orderPublicId를 포함해 주문별 캐시를 분리한다.
 */
export function useOrderDetail(orderPublicId: string) {
  const auth = useAuthStore()
  return useFetch<OrderDetail>(`/v1/orders/${orderPublicId}`, {
    key: `order-detail:${orderPublicId}`,
    baseURL: resolveApiBase(),
    headers: { Authorization: `Bearer ${auth.token}` },
  })
}
