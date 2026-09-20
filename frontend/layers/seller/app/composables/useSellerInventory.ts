import type {
  SellerInventoryAdjustRequest,
  SellerInventoryAdjustResponse,
  SellerInventoryListQuery,
  SellerInventoryListResponse,
} from '#layers/seller/app/types/seller-product'
import { toSellerInventoryApiParams } from '#layers/seller/app/lib/seller-inventory-query'

/**
 * 셀러 재고 API 호출 모음(Track 90-C-3·90-C-1 목록 + 기존 입출고 쓰기 D-112). 전부 useSellerApi(seller_token Bearer·401/403 SELLER_SUSPENDED 분기)
 * 경유이며 상태(로딩·에러)는 호출부(페이지·다이얼로그)가 소유한다.
 */
export function useSellerInventory() {
  const api = useSellerApi()

  function list(query: SellerInventoryListQuery): Promise<SellerInventoryListResponse> {
    return api<SellerInventoryListResponse>('/v1/seller/inventories', { query: toSellerInventoryApiParams(query) })
  }

  /** 입고(+quantity). 타 셀러·미존재 404·qty≤0 400·정지 셀러 403은 throw. */
  function markInbound(variantPublicId: string, body: SellerInventoryAdjustRequest): Promise<SellerInventoryAdjustResponse> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다.
    const path: string = `/v1/seller/inventories/${variantPublicId}/mark-inbound`
    return api<SellerInventoryAdjustResponse>(path, { method: 'POST', body })
  }

  /** 출고(−quantity). 실물·가용 부족 422(INVENTORY_INVARIANT_VIOLATION)·타 셀러 404·정지 셀러 403은 throw. */
  function markOutbound(variantPublicId: string, body: SellerInventoryAdjustRequest): Promise<SellerInventoryAdjustResponse> {
    const path: string = `/v1/seller/inventories/${variantPublicId}/mark-outbound`
    return api<SellerInventoryAdjustResponse>(path, { method: 'POST', body })
  }

  return { list, markInbound, markOutbound }
}
