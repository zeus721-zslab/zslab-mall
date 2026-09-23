import type { AdminSettlementItemType, AdminSettlementStatus } from '#layers/admin/app/lib/constants/admin-settlement'

/**
 * 관리자 정산 API 타입(Track 85 FE·D-179 BE 계약 1:1·backend settlement/controller/response 실측). nullable 필드는 BE NON_NULL 직렬화로
 * 생략될 수 있어 optional. 시각 문자열은 KST 오프셋 ISO(periodStart·periodEnd·paidAt·occurredAt)라 formatDateTime(앞 16자)으로만 표시하고,
 * scheduledPayDate는 LocalDate(yyyy-MM-dd)다. 금액은 원 단위 정수, 수수료율은 basis-point 정수(1000 = 10.00%).
 */

/** 셀러 참조(BE SettlementSellerRef). */
export interface AdminSettlementSellerRef {
  publicId: string
  companyName: string
}

/** 목록 행(BE AdminSettlementSummaryResponse·14필드). 셀러별 이력 API도 같은 행을 내린다. */
export interface AdminSettlementSummary {
  id: number
  seller: AdminSettlementSellerRef
  periodStart: string
  periodEnd: string
  grossAmount: number
  feeAmount: number
  refundAmount: number
  /** 이월 차감 = 앞선 음수 정산의 부족분(Track 104-3b). net = gross − fee − refund − carryover. */
  carryoverAmount: number
  netAmount: number
  status: AdminSettlementStatus
  scheduledPayDate?: string
  paidAt?: string
  /** 셀러의 현재 주 정산계좌 존재 여부(지급 가능 판정·목록 "계좌" 컬럼). */
  bankAccountRegistered: boolean
  saleItemCount: number
}

/** 월 합계(BE SettlementMonthlyTotals·필터와 무관한 해당 월 전체). */
export interface AdminSettlementMonthlyTotals {
  grossAmount: number
  feeAmount: number
  refundAmount: number
  carryoverAmount: number
  netAmount: number
  pendingCount: number
  confirmedCount: number
  paidCount: number
}

/** GET /admin/settlements 응답(PagedResponse 5필드 + totals). */
export interface AdminSettlementListResponse {
  items: AdminSettlementSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
  totals: AdminSettlementMonthlyTotals
}

/** 페이징 봉투(BE PagedResponse·품목·셀러별 이력 공용). */
export interface AdminSettlementPage<T> {
  items: T[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 셀러 연락처(BE 마스킹 완료본·SettlementSellerContactResponse). */
export interface AdminSettlementSellerContact {
  contactEmail?: string
  contactPhone?: string
}

/** 정산계좌(BE SettlementBankAccountResponse). snapshot=true면 지급 시점 스냅샷, false면 셀러의 현재 주계좌. 계좌번호는 끝 4자리만. */
export interface AdminSettlementBankAccount {
  id: number
  bankCode: string
  accountHolder: string
  accountNumberSuffix: string
  snapshot: boolean
}

/** 상세(BE AdminSettlementDetailResponse = Summary + 환불·이월 건수·연락처·계좌). */
export interface AdminSettlementDetail extends AdminSettlementSummary {
  refundItemCount: number
  carryoverItemCount: number
  sellerContact?: AdminSettlementSellerContact
  bankAccount?: AdminSettlementBankAccount
}

/**
 * 품목 스냅샷(BE SettlementItemResponse). refundId는 REFUND만, optionLabel은 옵션 없는 상품이면 생략.
 * CARRYOVER는 주문 품목이 없어 orderItemId·orderPublicId·productName·quantity가 생략된다.
 */
export interface AdminSettlementItem {
  id: number
  itemType: AdminSettlementItemType
  orderItemId?: number
  refundId?: number
  orderPublicId?: string
  productName?: string
  optionLabel?: string
  quantity?: number
  amount: number
  commissionRate: number
  feeAmount: number
  occurredAt: string
}

/** POST /admin/settlements 응답(BE SettlementBatchResponse). settlements는 생성분만(skip 제외). */
export interface AdminSettlementBatchResponse {
  year: number
  month: number
  periodStart: string
  periodEnd: string
  createdCount: number
  settlements: {
    settlementId: number
    sellerId: number
    grossAmount: number
    feeAmount: number
    refundAmount: number
    carryoverAmount: number
    netAmount: number
    scheduledPayDate?: string
  }[]
}

/** POST /admin/settlements/{id}/confirm·/pay 응답(BE SettlementTransitionResponse). */
export interface AdminSettlementTransitionResponse {
  settlementId: number
  status: AdminSettlementStatus
  paidAt?: string
}

/** POST /admin/settlements/{id}/regenerate 응답(BE SettlementRegenerateResponse·flat). deletedOnly=true면 settlementId 이하 생략. */
export interface AdminSettlementRegenerateResponse {
  deletedSettlementId: number
  deletedOnly: boolean
  settlementId?: number
  grossAmount?: number
  feeAmount?: number
  refundAmount?: number
  carryoverAmount?: number
  netAmount?: number
}

/** 목록 화면 상태(URL query 단일 소스). year·month는 항상 URL에 싣는다(기본 = 지난달). */
export interface AdminSettlementListQuery {
  year: number
  month: number
  status: AdminSettlementStatus | null
  keyword: string
  page: number
  size: number
}

/** BE GET /admin/settlements 쿼리 파라미터(null·빈 값은 제외). */
export type AdminSettlementApiParams = Record<string, string | number>
