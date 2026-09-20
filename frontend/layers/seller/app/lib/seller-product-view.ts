import type { SellerInventorySummary } from '#layers/seller/app/types/seller-product'
import { SELLER_INVENTORY_REASON_MAX } from '#layers/seller/app/lib/constants/seller-product'

/**
 * 셀러 상품·재고 화면 표시·검증 순수 함수(Track 90-C-3·seller-order-view 패턴·vitest 대상). 폼 검증은 BE Bean Validation·도메인 규칙과 같은 한도로
 * 400을 예방한다(quantity 양수는 BE Service가 IllegalArgumentException→400·reason @NotBlank @Size(255)).
 */

/** 가용 재고가 0 이하인 행(품절·강조 대상). */
export function isOutOfStock(item: Pick<SellerInventorySummary, 'quantityAvailable'>): boolean {
  return item.quantityAvailable <= 0
}

export interface InventoryAdjustFormInput {
  quantity: string
  reason: string
}

/** 입출고 폼 검증(필드 → 메시지). quantity는 양의 정수·reason은 필수·255자 이하. */
export function validateInventoryAdjustForm(input: InventoryAdjustFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  const quantity = input.quantity.trim()
  if (quantity === '' || !/^\d+$/.test(quantity) || Number(quantity) <= 0) errors.quantity = '수량은 1 이상의 정수여야 합니다.'
  const reason = input.reason.trim()
  if (reason === '') errors.reason = '사유를 입력하세요.'
  else if (reason.length > SELLER_INVENTORY_REASON_MAX) errors.reason = `사유는 ${SELLER_INVENTORY_REASON_MAX}자 이하여야 합니다.`
  return errors
}

/** 재고 행 한 줄 표기: "상품명 (옵션)". 옵션 없으면 괄호 생략·상품명 없으면(삭제 상품) 대시. */
export function inventoryItemLabel(item: Pick<SellerInventorySummary, 'productName' | 'optionLabel'>): string {
  const name = item.productName ?? '—'
  return item.optionLabel ? `${name} (${item.optionLabel})` : name
}
