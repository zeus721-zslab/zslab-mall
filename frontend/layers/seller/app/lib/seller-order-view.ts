import type { SellerOrderItemSummary } from '#layers/seller/app/types/seller-order'
import { claimStatusLabel, claimTypeLabel } from '~/lib/constants/claim'
import { SELLER_SHIPPABLE_ITEM_STATUSES, SELLER_TRACKING_NO_MAX } from '#layers/seller/app/lib/constants/seller-order'

/**
 * 셀러 주문(품목) 화면 표시·검증 순수 함수(Track 90-B-3·관리자 admin-order-view 복제·vitest 대상). 출고 대상 판정은 BE 규칙
 * (OrderShippingService.prepareShipment·PAID만)과 1:1이며, 폼 검증은 BE Bean Validation과 같은 한도로 400을 예방한다(도메인 검증은 BE 422).
 */

/** 출고(prepare-shipment) 가능 품목: PAID. PREPARING 이후는 이미 송장이 있다. */
export function canPrepareShipment(item: Pick<SellerOrderItemSummary, 'itemStatus'>): boolean {
  return SELLER_SHIPPABLE_ITEM_STATUSES.includes(item.itemStatus)
}

/** 배송완료(mark-delivered) 가능 품목: 원 발송 배송이 SHIPPING. 배송 화면과 같은 판정. */
export function canMarkDelivered(item: Pick<SellerOrderItemSummary, 'delivery'>): boolean {
  return item.delivery?.status === 'SHIPPING'
}

export interface ShipmentFormInput {
  carrier: string | null
  trackingNo: string
}

/** 출고 폼 검증(필드 → 메시지). BE @NotBlank·@Size(100)과 동일 한도. */
export function validateShipmentForm(input: ShipmentFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.carrier) errors.carrier = '택배사를 선택하세요.'
  const trackingNo = input.trackingNo.trim()
  if (trackingNo === '') errors.trackingNo = '송장번호를 입력하세요.'
  else if (trackingNo.length > SELLER_TRACKING_NO_MAX) errors.trackingNo = `송장번호는 ${SELLER_TRACKING_NO_MAX}자 이하여야 합니다.`
  return errors
}

/** 품목 한 줄 표기: "상품명 (옵션) · N개". 옵션 없으면 괄호 생략. */
export function itemLabel(item: Pick<SellerOrderItemSummary, 'productName' | 'optionLabel' | 'quantity'>): string {
  const option = item.optionLabel ? ` (${item.optionLabel})` : ''
  return `${item.productName}${option} · ${item.quantity}개`
}

/** 품목 행 클레임 칩 라벨: "반품 요청" 형태(유형 + 상태). 2건 이상이면 " · N건"을 붙여 이력이 더 있음을 알린다(Track 90-D-1). */
export function claimChipLabel(item: Pick<SellerOrderItemSummary, 'claim' | 'claimCount'>): string | null {
  if (!item.claim) return null
  const base = `${claimTypeLabel(item.claim.type)} ${claimStatusLabel(item.claim.status)}`
  return item.claimCount > 1 ? `${base} · ${item.claimCount}건` : base
}
