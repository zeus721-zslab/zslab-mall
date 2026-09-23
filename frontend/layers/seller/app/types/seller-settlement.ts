import type { SellerSettlementItemType, SellerSettlementStatus } from '#layers/seller/app/lib/constants/seller-settlement'

/**
 * 셀러 정산 API 타입(Track 90-B-3·Track 85 BE 계약 1:1·backend settlement/controller/response/SellerSettlement*·SettlementItemResponse 실측).
 * nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional. 시각 문자열은 KST 오프셋 ISO(periodStart·periodEnd·paidAt·occurredAt)라
 * formatDateTime으로만 표시하고, scheduledPayDate는 LocalDate(yyyy-MM-dd)다. 금액은 원 단위 정수, 수수료율은 basis-point 정수(1000 = 10.00%).
 */

/** 목록 행(BE SellerSettlementSummaryResponse·11필드·본인 CONFIRMED·PAID만). */
export interface SellerSettlementSummary {
  id: number
  periodStart: string
  periodEnd: string
  grossAmount: number
  feeAmount: number
  refundAmount: number
  /** 이월 차감 = 앞선 음수 정산의 부족분(Track 104-3b). net = gross − fee − refund − carryover. */
  carryoverAmount: number
  netAmount: number
  status: SellerSettlementStatus
  scheduledPayDate?: string
  paidAt?: string
}

/** 정산계좌(BE SettlementBankAccountResponse). snapshot=true면 지급 시점 스냅샷, false면 현재 주계좌. 계좌번호는 끝 4자리만. */
export interface SellerSettlementBankAccount {
  id: number
  bankCode: string
  accountHolder: string
  accountNumberSuffix: string
  snapshot: boolean
}

/** 상세(BE SellerSettlementDetailResponse = 목록 행 + 품목 건수 3 + 계좌). */
export interface SellerSettlementDetail extends SellerSettlementSummary {
  saleItemCount: number
  refundItemCount: number
  carryoverItemCount: number
  bankAccount?: SellerSettlementBankAccount
}

/**
 * 품목 스냅샷(BE SettlementItemResponse). refundId는 REFUND만, optionLabel은 옵션 없는 상품이면 생략.
 * CARRYOVER는 주문 품목이 없어 orderItemId·orderPublicId·productName·quantity가 생략된다.
 */
export interface SellerSettlementItem {
  id: number
  itemType: SellerSettlementItemType
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

/** 페이징 봉투(BE PagedResponse·목록·품목 공용). */
export interface SellerSettlementPage<T> {
  items: T[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}
