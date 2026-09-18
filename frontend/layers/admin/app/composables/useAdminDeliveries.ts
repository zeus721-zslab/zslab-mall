import type {
  AdminDeliveryDetail,
  AdminDeliveryListQuery,
  AdminDeliveryListResponse,
  AdminDeliveryTrackingCorrectionRequest,
} from '#layers/admin/app/types/admin-delivery'
import type { AdminDeliveryResponse } from '#layers/admin/app/types/admin-order'
import { toAdminDeliveryApiParams } from '#layers/admin/app/lib/admin-delivery-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 배송 관리 API 호출 모음(FE-37·Track 89-B BE). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며 상태(로딩·에러)는
 * 호출부(페이지·다이얼로그)가 소유한다.
 */
export function useAdminDeliveries() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminOrders 선례).
  function deliveryPath(deliveryPublicId: string, suffix = ''): string {
    return `/v1/admin/deliveries/${deliveryPublicId}${suffix}`
  }

  function list(query: AdminDeliveryListQuery): Promise<AdminDeliveryListResponse> {
    return api<AdminDeliveryListResponse>('/v1/admin/deliveries', { query: toAdminDeliveryApiParams(query) })
  }

  function detail(deliveryPublicId: string): Promise<AdminDeliveryDetail> {
    return api<AdminDeliveryDetail>(deliveryPath(deliveryPublicId))
  }

  /** 송장 정정. SHIPPING 외 422(DELIVERY_INVALID_STATE)·타 배송과 송장 중복 409(DELIVERY_TRACKING_NO_CONFLICT)는 throw. */
  function correctTracking(deliveryPublicId: string, body: AdminDeliveryTrackingCorrectionRequest): Promise<AdminDeliveryResponse> {
    return api<AdminDeliveryResponse>(deliveryPath(deliveryPublicId, '/tracking'), { method: 'PATCH', body })
  }

  return { list, detail, correctTracking }
}
