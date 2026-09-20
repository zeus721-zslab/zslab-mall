import type {
  SellerDeliveryResponse,
  SellerOrderItemDetail,
  SellerOrderItemListResponse,
  SellerOrderListQuery,
  SellerShipmentRequest,
} from '#layers/seller/app/types/seller-order'
import { toSellerOrderApiParams } from '#layers/seller/app/lib/seller-order-query'

/**
 * 셀러 주문(품목) API 호출 모음(Track 90-B-3·D-191 조회 + 기존 쓰기). 전부 useSellerApi(seller_token Bearer·401/403 SELLER_SUSPENDED 분기) 경유이며
 * 상태(로딩·에러)는 호출부(페이지·다이얼로그)가 소유한다. 출고·배송완료는 셀러 기존 쓰기 경로(/order-items·/deliveries·prefix 밖)다.
 */
export function useSellerOrders() {
  const api = useSellerApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(관리자 useAdminOrders 선례).
  function list(query: SellerOrderListQuery): Promise<SellerOrderItemListResponse> {
    return api<SellerOrderItemListResponse>('/v1/seller/order-items', { query: toSellerOrderApiParams(query) })
  }

  function detail(orderItemPublicId: string): Promise<SellerOrderItemDetail> {
    const path: string = `/v1/seller/order-items/${orderItemPublicId}`
    return api<SellerOrderItemDetail>(path)
  }

  /** 출고(송장 등록). 타 셀러·미존재 404·비-PAID 422(ORDER_ITEM_INVALID_STATE)·클레임 진행 422·정지 셀러 403 SELLER_SUSPENDED는 throw. */
  function prepareShipment(orderItemPublicId: string, body: SellerShipmentRequest): Promise<SellerDeliveryResponse> {
    const path: string = `/v1/order-items/${orderItemPublicId}/prepare-shipment`
    return api<SellerDeliveryResponse>(path, { method: 'POST', body })
  }

  /** 배송완료. 타 셀러·미존재 404·비-SHIPPING 422(DELIVERY_INVALID_STATE)·정지 셀러 403은 throw. */
  function markDelivered(deliveryPublicId: string): Promise<SellerDeliveryResponse> {
    const path: string = `/v1/deliveries/${deliveryPublicId}/mark-delivered`
    return api<SellerDeliveryResponse>(path, { method: 'POST' })
  }

  return { list, detail, prepareShipment, markDelivered }
}
