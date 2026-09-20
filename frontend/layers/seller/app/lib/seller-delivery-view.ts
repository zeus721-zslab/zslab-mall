import type { SellerSemantic } from '#layers/seller/app/lib/constants/semantic'
import type { SellerDeliveryStatus } from '#layers/seller/app/lib/constants/seller-order'
import type { SellerDeliverySummary } from '#layers/seller/app/types/seller-delivery'
import {
  SELLER_DELIVERY_ACTIONABLE_STATUSES,
  SELLER_DELIVERY_CLAIM_LABEL,
  SELLER_DELIVERY_CORRECTION_REASON_MAX,
  SELLER_DELIVERY_DIRECTION_SEMANTIC,
} from '#layers/seller/app/lib/constants/seller-delivery'
import { SELLER_TRACKING_NO_MAX } from '#layers/seller/app/lib/constants/seller-order'

/** 셀러 배송 화면 순수 판정(Track 90-B-3·관리자 admin-delivery-view 복제). 컴포넌트가 아니라 여기 두어 vitest로 고정한다. */

/**
 * 클레임 연계 배지(방향 × 클레임 유형). 원 발송(claimType 없음)은 null. 회수(RETURN)는 warning, 클레임 발송(교환품·재발송)도 원 발송과 구분하기 위해
 * warning으로 둔다(관리자 D-184 동형).
 */
export function deliveryClaimChip(item: Pick<SellerDeliverySummary, 'direction' | 'claimType'>): { text: string; semantic: SellerSemantic } | null {
  if (!item.claimType) return null
  return {
    text: SELLER_DELIVERY_CLAIM_LABEL[item.direction][item.claimType],
    semantic: item.direction === 'RETURN' ? SELLER_DELIVERY_DIRECTION_SEMANTIC.RETURN : 'warning',
  }
}

/** 송장 정정 가능 여부(BE Delivery.correctTracking·SHIPPING만). */
export function canCorrectTracking(status: SellerDeliveryStatus): boolean {
  return SELLER_DELIVERY_ACTIONABLE_STATUSES.includes(status)
}

/** 배송완료 처리 가능 여부(BE markDeliveredBySeller·원 발송 SHIPPING만·클레임 연결 배송(교환품·재발송·회수)은 관리자 클레임 흐름·D-197). */
export function canMarkDelivered(item: Pick<SellerDeliverySummary, 'status' | 'direction' | 'claimType'>): boolean {
  return item.direction === 'OUTBOUND' && !item.claimType && SELLER_DELIVERY_ACTIONABLE_STATUSES.includes(item.status)
}

/** 행 액션 메뉴 노출 여부(배송완료 또는 송장 정정 중 하나라도 가능). */
export function hasRowActions(item: Pick<SellerDeliverySummary, 'status' | 'direction' | 'claimType'>): boolean {
  return canMarkDelivered(item) || canCorrectTracking(item.status)
}

/** 송장 정정 불가 사유 툴팁 문구(가능하면 null). */
export function trackingCorrectionBlockedReason(status: SellerDeliveryStatus): string | null {
  if (canCorrectTracking(status)) return null
  return status === 'DELIVERED'
    ? '배송완료된 배송은 송장을 수정할 수 없습니다.'
    : '송장이 등록된 배송중 상태에서만 수정할 수 있습니다.'
}

export interface TrackingCorrectionFormInput {
  carrier: string | null
  trackingNo: string
  reason: string
}

/** 송장 정정 폼 검증(BE @NotNull·@NotBlank·@Size(100/200)과 동일 한도). */
export function validateTrackingCorrectionForm(input: TrackingCorrectionFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.carrier) errors.carrier = '택배사를 선택하세요.'
  const trackingNo = input.trackingNo.trim()
  if (trackingNo === '') errors.trackingNo = '송장번호를 입력하세요.'
  else if (trackingNo.length > SELLER_TRACKING_NO_MAX) errors.trackingNo = `송장번호는 ${SELLER_TRACKING_NO_MAX}자 이하여야 합니다.`
  const reason = input.reason.trim()
  if (reason === '') errors.reason = '사유를 입력하세요.'
  else if (reason.length > SELLER_DELIVERY_CORRECTION_REASON_MAX) errors.reason = `사유는 ${SELLER_DELIVERY_CORRECTION_REASON_MAX}자 이하여야 합니다.`
  return errors
}
