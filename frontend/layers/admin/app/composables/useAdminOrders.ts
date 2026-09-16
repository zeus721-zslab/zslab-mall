import type {
  AdminClaimResponse,
  AdminDeliveryResponse,
  AdminOrderCancelRequest,
  AdminOrderCancelResponse,
  AdminOrderDetail,
  AdminOrderListQuery,
  AdminOrderListResponse,
  AdminShipmentRequest,
} from '#layers/admin/app/types/admin-order'
import type { AdminClaimRejectBody } from '#layers/admin/app/types/admin-claim'
import { toAdminOrderApiParams } from '#layers/admin/app/lib/admin-order-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 주문 관리 API 호출 모음(FE-27·Track 79 BE). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며 상태(로딩·에러)는
 * 호출부(페이지·다이얼로그)가 소유한다. 송장·배송완료·클레임 승인/거절은 주문이 아니라 품목·배송·클레임 단위 경로다.
 */
export function useAdminOrders() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminProducts 선례).
  function orderPath(orderPublicId: string, suffix = ''): string {
    return `/v1/admin/orders/${orderPublicId}${suffix}`
  }

  function list(query: AdminOrderListQuery): Promise<AdminOrderListResponse> {
    return api<AdminOrderListResponse>('/v1/admin/orders', { query: toAdminOrderApiParams(query) })
  }

  function detail(orderPublicId: string): Promise<AdminOrderDetail> {
    return api<AdminOrderDetail>(orderPath(orderPublicId))
  }

  function cancel(orderPublicId: string, body: AdminOrderCancelRequest): Promise<AdminOrderCancelResponse> {
    return api<AdminOrderCancelResponse>(orderPath(orderPublicId, '/cancel'), { method: 'POST', body })
  }

  function prepareShipment(orderItemPublicId: string, body: AdminShipmentRequest): Promise<AdminDeliveryResponse> {
    const path: string = `/v1/admin/orders/items/${orderItemPublicId}/prepare-shipment`
    return api<AdminDeliveryResponse>(path, { method: 'POST', body })
  }

  function markDelivered(deliveryPublicId: string): Promise<AdminDeliveryResponse> {
    const path: string = `/v1/admin/deliveries/${deliveryPublicId}/mark-delivered`
    return api<AdminDeliveryResponse>(path, { method: 'POST' })
  }

  /** 승인 body는 EXCHANGE 차액용(선택)이라 FE-27은 보내지 않는다(BE required=false). */
  function approveClaim(claimPublicId: string): Promise<AdminClaimResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/approve`
    return api<AdminClaimResponse>(path, { method: 'POST' })
  }

  /** 거부 body는 사유 코드 필수·메모 선택(FE-28·Track 80 D-169·BE ClaimRejectRequest @Valid). */
  function rejectClaim(claimPublicId: string, body: AdminClaimRejectBody): Promise<AdminClaimResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/reject`
    return api<AdminClaimResponse>(path, { method: 'POST', body })
  }

  return { list, detail, cancel, prepareShipment, markDelivered, approveClaim, rejectClaim }
}
