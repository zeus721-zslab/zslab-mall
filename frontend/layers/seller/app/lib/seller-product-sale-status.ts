import type { SellerProductStatus, SellerSaleAction, SellerSaleStopSource } from '#layers/seller/app/lib/constants/seller-product'
import { SELLER_SALE_ACTION_LABEL } from '#layers/seller/app/lib/constants/seller-product'

/**
 * 셀러 판매 상태 액션 분기 순수 함수(Track 96-5·D-206·FE-57). 상태·중지 주체로 (1) 어떤 액션이 가능한지 (2) 비활성이면 왜인지를 정한다.
 * - SALE → 판매중지(STOP)
 * - STOPPED + SELLER 중지 → 재판매(RESUME)
 * - STOPPED + ADMIN 중지(또는 주체 불명) → 재판매 비활성 + 운영자 문의 안내(fail-closed·BE는 422 PRODUCT_STOPPED_BY_ADMIN)
 * - 그 외(PENDING·REJECTED·DRAFT·APPROVED·HIDDEN) → 액션 없음(메뉴 미노출)
 */
export interface SellerSaleActionState {
  action: SellerSaleAction | null
  disabled: boolean
  note: string | null
}

export const SELLER_ADMIN_STOPPED_NOTE = '관리자가 판매중지한 상품입니다. 재판매는 운영자에게 문의하세요.'

export function resolveSellerSaleAction(
  status: SellerProductStatus,
  saleStopSource: SellerSaleStopSource | undefined,
): SellerSaleActionState {
  if (status === 'SALE') return { action: 'STOP', disabled: false, note: null }
  if (status === 'STOPPED') {
    if (saleStopSource === 'SELLER') return { action: 'RESUME', disabled: false, note: null }
    return { action: 'RESUME', disabled: true, note: SELLER_ADMIN_STOPPED_NOTE }
  }
  return { action: null, disabled: false, note: null }
}

/** 행 메뉴 노출 여부 = 액션이 하나라도 있는가(비활성 재판매도 사유 안내를 위해 노출). */
export function hasSellerSaleAction(status: SellerProductStatus, saleStopSource: SellerSaleStopSource | undefined): boolean {
  return resolveSellerSaleAction(status, saleStopSource).action !== null
}

/** 액션 → BE sale-status body status. */
export function toSaleStatusTarget(action: SellerSaleAction): 'SALE' | 'STOPPED' {
  return action === 'STOP' ? 'STOPPED' : 'SALE'
}

/** 확인 다이얼로그 본문. 판매중지는 구매자 노출·담기·주문 차단, 재판매는 즉시 재노출을 알린다. */
export function saleActionConfirmMessage(action: SellerSaleAction, productName: string): string {
  const label = SELLER_SALE_ACTION_LABEL[action]
  if (action === 'STOP') {
    return `"${productName}"을(를) ${label}합니다. 구매자 목록에서 즉시 숨겨지고 장바구니 담기·주문이 차단됩니다(진행 중 주문은 영향 없음). 재판매는 이 화면에서 직접 할 수 있습니다.`
  }
  return `"${productName}"을(를) ${label}합니다. 구매자 목록에 즉시 다시 노출되고 담기·주문이 가능해집니다.`
}
