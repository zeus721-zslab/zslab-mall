import type {
  SellerDeliveryListQuery,
  SellerDeliveryListResponse,
  SellerDeliveryTrackingCorrectionRequest,
} from '#layers/seller/app/types/seller-delivery'
import type { SellerDeliveryResponse } from '#layers/seller/app/types/seller-order'
import { toSellerDeliveryApiParams } from '#layers/seller/app/lib/seller-delivery-query'

/**
 * 셀러 배송 API 호출 모음(Track 90-B-3·D-191 목록·송장 정정 + 기존 배송완료). 전부 useSellerApi(seller_token Bearer·401/403 SELLER_SUSPENDED 분기)
 * 경유이며 상태(로딩·에러)는 호출부(페이지·다이얼로그)가 소유한다.
 */
export function useSellerDeliveries() {
  const api = useSellerApi()

  function list(query: SellerDeliveryListQuery): Promise<SellerDeliveryListResponse> {
    return api<SellerDeliveryListResponse>('/v1/seller/deliveries', { query: toSellerDeliveryApiParams(query) })
  }

  /** 송장 정정. SHIPPING 외 422(DELIVERY_INVALID_STATE)·타 배송과 송장 중복 409(DELIVERY_TRACKING_NO_CONFLICT)·타 셀러 404·정지 셀러 403은 throw. */
  function correctTracking(deliveryPublicId: string, body: SellerDeliveryTrackingCorrectionRequest): Promise<SellerDeliveryResponse> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다.
    const path: string = `/v1/seller/deliveries/${deliveryPublicId}/tracking`
    return api<SellerDeliveryResponse>(path, { method: 'PATCH', body })
  }

  /** 배송완료(셀러 기존 쓰기·/deliveries prefix). 비-SHIPPING 422·타 셀러 404·정지 셀러 403은 throw. */
  function markDelivered(deliveryPublicId: string): Promise<SellerDeliveryResponse> {
    const path: string = `/v1/deliveries/${deliveryPublicId}/mark-delivered`
    return api<SellerDeliveryResponse>(path, { method: 'POST' })
  }

  return { list, correctTracking, markDelivered }
}
