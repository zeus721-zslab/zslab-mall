import type {
  SellerSettlementDetail,
  SellerSettlementItem,
  SellerSettlementPage,
  SellerSettlementSummary,
} from '#layers/seller/app/types/seller-settlement'
import type { SellerSettlementItemType } from '#layers/seller/app/lib/constants/seller-settlement'

/**
 * 셀러 정산 API 호출 모음(Track 90-B-3·Track 85 BE·읽기 전용 3종). 전부 useSellerApi 경유이며 상태(로딩·에러)는 호출부가 소유한다.
 * 목록은 본인 CONFIRMED·PAID만(최신 기간순·필터 없음)·상세/품목은 미존재·타 셀러·PENDING 모두 404 SETTLEMENT_NOT_FOUND.
 */
export function useSellerSettlements() {
  const api = useSellerApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다.
  function settlementPath(id: number, suffix = ''): string {
    return `/v1/seller/settlements/${id}${suffix}`
  }

  function list(page: number, size: number): Promise<SellerSettlementPage<SellerSettlementSummary>> {
    return api<SellerSettlementPage<SellerSettlementSummary>>('/v1/seller/settlements', { query: { page, size } })
  }

  function get(id: number): Promise<SellerSettlementDetail> {
    return api<SellerSettlementDetail>(settlementPath(id))
  }

  function listItems(id: number, type: SellerSettlementItemType, page: number, size: number): Promise<SellerSettlementPage<SellerSettlementItem>> {
    return api<SellerSettlementPage<SellerSettlementItem>>(settlementPath(id, '/items'), { query: { type, page, size } })
  }

  return { list, get, listItems }
}
