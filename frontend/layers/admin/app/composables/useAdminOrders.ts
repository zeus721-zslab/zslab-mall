import type {
  AdminClaimResponse,
  AdminDeliveryResponse,
  AdminOrderCancelRequest,
  AdminOrderCancelResponse,
  AdminOrderDetail,
  AdminOrderListQuery,
  AdminOrderListResponse,
  AdminPaymentCancelRequest,
  AdminPaymentCancelResponse,
  AdminShipmentRequest,
} from '#layers/admin/app/types/admin-order'
import type { AdminClaimInspectBody, AdminClaimRejectBody, AdminRefundInitiateBody, AdminRefundInitiateResponse } from '#layers/admin/app/types/admin-claim'
import type { AdminAuditLogPage } from '#layers/admin/app/types/admin-audit'
import { toAdminOrderApiParams } from '#layers/admin/app/lib/admin-order-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 주문 관리 API 호출 모음(FE-27·Track 79 BE). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며 상태(로딩·에러)는
 * 호출부(페이지·다이얼로그)가 소유한다. 송장·배송완료·클레임 승인/거부는 주문이 아니라 품목·배송·클레임 단위 경로다.
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

  /** 승인은 body 없이 호출한다(교환 차액 refundAmount는 D-177로 폐기·값을 보내면 BE 400). */
  function approveClaim(claimPublicId: string): Promise<AdminClaimResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/approve`
    return api<AdminClaimResponse>(path, { method: 'POST' })
  }

  /** 거부 body는 사유 코드 필수·메모 선택(FE-28·Track 80 D-169·BE ClaimRejectRequest @Valid). */
  function rejectClaim(claimPublicId: string, body: AdminClaimRejectBody): Promise<AdminClaimResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/reject`
    return api<AdminClaimResponse>(path, { method: 'POST', body })
  }

  /** 반품 회수 확인(FE-29·Track 81-A). body 없음·422(비승인·회수 송장 부재)는 throw. */
  function confirmPickupClaim(claimPublicId: string): Promise<AdminClaimResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/confirm-pickup`
    return api<AdminClaimResponse>(path, { method: 'POST' })
  }

  /** 반품 검수(FE-29·Track 81-A). PASS restock 필수·FAIL 사유·재발송 송장 필수(400)·회수 전/재검수 422는 throw. */
  /** 교환품 발송 등록(FE-30·Track 83 D-177). 검수 합격한 교환만 200·그 외 422(CLAIM_STATE_INVALID)는 throw. 응답은 Delivery 관점. */
  function registerExchangeShipment(claimPublicId: string, body: AdminShipmentRequest): Promise<AdminDeliveryResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/register-exchange-shipment`
    return api<AdminDeliveryResponse>(path, { method: 'POST', body })
  }

  /**
   * 회수 송장 대행 등록(Track 101-A). 구매자가 올리지 않은 회수 송장을 운영자가 대신 넣는다. 구매자 경로와 같은 BE 도메인
   * 경로라 생성되는 RETURN 배송·구매자 화면 표시가 동일하다. 유형/상태 위반·중복 등록 422는 throw.
   */
  function registerReturnShipment(claimPublicId: string, body: AdminShipmentRequest): Promise<AdminDeliveryResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/return-shipment`
    return api<AdminDeliveryResponse>(path, { method: 'POST', body })
  }

  /** 클레임 처리 이력(Track 101-A·감사 로그 최신순). 미존재 클레임 404는 throw. */
  function claimAuditLogs(claimPublicId: string, page = 0, size = 20): Promise<AdminAuditLogPage> {
    const path: string = `/v1/admin/claims/${claimPublicId}/audit-logs`
    return api<AdminAuditLogPage>(path, { query: { page, size } })
  }

  function inspectClaim(claimPublicId: string, body: AdminClaimInspectBody): Promise<AdminClaimResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/inspect`
    return api<AdminClaimResponse>(path, { method: 'POST', body })
  }

  /** 수동 결제 취소(FE-36·Track 89-A·D-113 fallback). 전액 환불 미완료·이미 CANCELLED는 200 NO-OP(응답 status로 판별). */
  function markPaymentCancelled(paymentPublicId: string, body: AdminPaymentCancelRequest): Promise<AdminPaymentCancelResponse> {
    const path: string = `/v1/admin/payments/${paymentPublicId}/mark-cancelled`
    return api<AdminPaymentCancelResponse>(path, { method: 'POST', body })
  }

  /** 수동 환불 개시(FE-36·Track 89-A·D-106 fallback). 비승인 422(CLAIM_STATE_INVALID)·한도 초과 422(REFUND_INVARIANT_VIOLATION)는 throw. */
  function initiateRefund(claimPublicId: string, body: AdminRefundInitiateBody): Promise<AdminRefundInitiateResponse> {
    const path: string = `/v1/admin/claims/${claimPublicId}/initiate-refund`
    return api<AdminRefundInitiateResponse>(path, { method: 'POST', body })
  }

  return {
    list, detail, cancel, prepareShipment, markDelivered, approveClaim, rejectClaim, confirmPickupClaim, inspectClaim,
    registerExchangeShipment, registerReturnShipment, claimAuditLogs, markPaymentCancelled, initiateRefund,
  }
}
