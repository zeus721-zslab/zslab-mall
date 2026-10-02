import type { ConfirmPurchaseResponse, OrderDetail, OrderStatusSummary, OrderSummary, PagedResponse } from '~/types/order'
import type { OrderItemStatusFilter } from '~/lib/constants/order-tabs'

/** 목록 기본 페이지 크기(FE-80: 주문 카드가 품목 행을 모두 펼쳐 20은 길다 · BE 기본 20·최대 100 범위 안). */
const DEFAULT_PAGE_SIZE = 10

/**
 * 구매자 주문 목록 조회(GET /api/v1/orders?page&size). BUYER 전용 API라 구매자 래퍼(useBuyerApi · 구매자 쿠키 인증)로 호출한다
 * (permitAll인 useProductDetail을 복제하지 않는다). page는 Ref로 받아 변경 시 useFetch가 SSR 페이로드와 함께 재조회한다.
 * 401 등 인증 실패는 error로 노출해 소비 페이지가 /login으로 유도한다.
 * itemStatus(D-224·FE-80)는 품목 상태 필터이며 null이면 쿼리에서 뺀다(키만 붙은 ?itemStatus를 보내지 않도록 undefined로 바꾼다).
 */
export function useOrderList(
  page: Ref<number>,
  itemStatus: Ref<OrderItemStatusFilter | null> = ref(null),
  size: number = DEFAULT_PAGE_SIZE,
) {
  const itemStatusQuery = computed(() => itemStatus.value ?? undefined)
  return useFetch<PagedResponse<OrderSummary>>('/v1/orders', {
    key: 'order-list',
    $fetch: useBuyerApi(),
    query: { page, size, itemStatus: itemStatusQuery },
  })
}

/**
 * 구매자 최근 주문 size건(GET /api/v1/orders?page=0&size·마이페이지 홈 FE-72). 주문 내역 페이지(useOrderList)와 조회 조건이
 * 달라 캐시 키를 나눈다(같은 키면 두 화면이 한 데이터를 공유한다).
 */
export function useRecentOrders(size: number) {
  return useFetch<PagedResponse<OrderSummary>>('/v1/orders', {
    key: 'recent-orders',
    $fetch: useBuyerApi(),
    query: { page: 0, size },
  })
}

/** 구매자 주문 현황 요약(GET /api/v1/orders/summary·D-223·마이페이지 홈 FE-72). BUYER 전용이라 구매자 래퍼로 호출. */
export function useOrderStatusSummary() {
  return useFetch<OrderStatusSummary>('/v1/orders/summary', {
    key: 'order-status-summary',
    $fetch: useBuyerApi(),
  })
}

/**
 * 구매자 주문 단건 조회(GET /api/v1/orders/{orderPublicId}). BUYER 전용이라 구매자 래퍼로 호출. 미존재·타인 주문은 BE가 404
 * (존재 은닉)를 반환하며 useFetch가 error로 노출한다. key는 orderPublicId를 포함해 주문별 캐시를 분리한다.
 * 주문 상세는 상태 전이(결제·배송·클레임)가 잦아 재방문 시 항상 재검증한다. 기본 getCachedData가 그 동작이다 — hydration 중에만
 * SSR payload를 재사용하고 클라이언트 진입은 다시 조회한다. 예전처럼 `() => undefined`로 덮으면 hydration 중에도 payload를 버려
 * 첫 렌더가 스켈레톤이 되고 hydration mismatch가 난다(W1).
 */
export function useOrderDetail(orderPublicId: string) {
  return useFetch<OrderDetail>(`/v1/orders/${orderPublicId}`, {
    key: `order-detail:${orderPublicId}`,
    $fetch: useBuyerApi(),
  })
}

/**
 * 구매자 주문 품목 구매확정(POST /api/v1/orders/{orderPublicId}/items/{orderItemPublicId}/confirm·BUYER 전용·Track 96-1 FE-53).
 * useClaim.registerReturnShipment 패턴: 404(타인·미존재)·422(DELIVERED 아님)·401은 throw해 호출부가 타입별로 처리한다.
 */
export function useOrderActions() {
  const api = useBuyerApi()

  function confirmPurchase(orderPublicId: string, orderItemPublicId: string): Promise<ConfirmPurchaseResponse> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useSellerApi 호출부 선례).
    const path: string = `/v1/orders/${orderPublicId}/items/${orderItemPublicId}/confirm`
    return api<ConfirmPurchaseResponse>(path, {
      method: 'POST',
    })
  }

  return { confirmPurchase }
}
