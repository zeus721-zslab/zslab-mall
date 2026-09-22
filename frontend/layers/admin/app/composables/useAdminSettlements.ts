import type {
  AdminSettlementBatchResponse,
  AdminSettlementDetail,
  AdminSettlementItem,
  AdminSettlementListQuery,
  AdminSettlementListResponse,
  AdminSettlementPage,
  AdminSettlementRegenerateResponse,
  AdminSettlementSummary,
  AdminSettlementTransitionResponse,
} from '#layers/admin/app/types/admin-settlement'
import type { AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import type { AdminAuditLogPage } from '#layers/admin/app/types/admin-audit'
import type { AdminSettlementItemType } from '#layers/admin/app/lib/constants/admin-settlement'
import { toAdminSettlementApiParams } from '#layers/admin/app/lib/admin-settlement-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 정산 API 호출 모음(Track 85 FE·D-179 BE·useAdminMembers 패턴). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며
 * 상태(로딩·에러)는 호출부(페이지·다이얼로그)가 소유한다. 셀러 선택 목록은 상품 등록 폼과 같은 GET /admin/sellers를 쓴다.
 */
export function useAdminSettlements() {
  const api = useAdminApi()

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminOrders 선례).
  function settlementPath(id: number, suffix = ''): string {
    return `/v1/admin/settlements/${id}${suffix}`
  }

  function list(query: AdminSettlementListQuery): Promise<AdminSettlementListResponse> {
    return api<AdminSettlementListResponse>('/v1/admin/settlements', { query: toAdminSettlementApiParams(query) })
  }

  function get(id: number): Promise<AdminSettlementDetail> {
    return api<AdminSettlementDetail>(settlementPath(id))
  }

  function listItems(id: number, type: AdminSettlementItemType, page: number, size: number): Promise<AdminSettlementPage<AdminSettlementItem>> {
    return api<AdminSettlementPage<AdminSettlementItem>>(settlementPath(id, '/items'), { query: { type, page, size } })
  }

  /** 셀러 월별 정산 이력(최신 기간순). 셀러 식별자는 slr_ publicId·미존재 404 SELLER_NOT_FOUND. */
  function listBySeller(sellerPublicId: string, page: number, size: number): Promise<AdminSettlementPage<AdminSettlementSummary>> {
    const path: string = `/v1/admin/sellers/${sellerPublicId}/settlements`
    return api<AdminSettlementPage<AdminSettlementSummary>>(path, { query: { page, size } })
  }

  function sellers(): Promise<AdminSellerSummary[]> {
    return api<AdminSellerSummary[]>('/v1/admin/sellers')
  }

  /** 선택 월 정산 배치 생성(201). 기간 범위·미마감 400 SETTLEMENT_PERIOD_INVALID·동시 실행 중복 409. */
  function create(year: number, month: number): Promise<AdminSettlementBatchResponse> {
    return api<AdminSettlementBatchResponse>('/v1/admin/settlements', { method: 'POST', body: { year, month } })
  }

  function confirm(id: number): Promise<AdminSettlementTransitionResponse> {
    return api<AdminSettlementTransitionResponse>(settlementPath(id, '/confirm'), { method: 'POST' })
  }

  function pay(id: number): Promise<AdminSettlementTransitionResponse> {
    return api<AdminSettlementTransitionResponse>(settlementPath(id, '/pay'), { method: 'POST' })
  }

  function regenerate(id: number, reason: string): Promise<AdminSettlementRegenerateResponse> {
    return api<AdminSettlementRegenerateResponse>(settlementPath(id, '/regenerate'), { method: 'POST', body: { reason } })
  }

  /** 정산 처리 이력(Track 101-A·감사 로그 최신순). 미존재 정산 id는 빈 페이지다(BE에서 존재 재확인 없음). */
  function auditLogs(id: number, page = 0, size = 20): Promise<AdminAuditLogPage> {
    return api<AdminAuditLogPage>(settlementPath(id, '/audit-logs'), { query: { page, size } })
  }

  return { list, get, listItems, listBySeller, sellers, create, confirm, pay, regenerate, auditLogs }
}
