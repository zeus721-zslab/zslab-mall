import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type { AdminDeliveryStatus } from '#layers/admin/app/lib/constants/admin-order'
import type { AdminDeliverySummary } from '#layers/admin/app/types/admin-delivery'
import {
  ADMIN_DELIVERY_CLAIM_LABEL,
  ADMIN_DELIVERY_CORRECTABLE_STATUSES,
  ADMIN_DELIVERY_DIRECTION_SEMANTIC,
} from '#layers/admin/app/lib/constants/admin-delivery'

/** 배송 관리 화면 순수 판정(FE-37). 컴포넌트가 아니라 여기 두어 vitest로 고정한다. */

/**
 * 클레임 연계 배지(방향 × 클레임 유형). 원 발송(claimType 없음)은 null. 회수(RETURN)는 warning, 클레임 발송(교환품·재발송)도
 * 원 발송과 구분하기 위해 warning으로 둔다(D-184·관리자 의미색 4종에 회색 없음).
 */
export function deliveryClaimChip(item: Pick<AdminDeliverySummary, 'direction' | 'claimType'>): { text: string; semantic: AdminSemantic } | null {
  if (!item.claimType) return null
  return {
    text: ADMIN_DELIVERY_CLAIM_LABEL[item.direction][item.claimType],
    semantic: item.direction === 'RETURN' ? ADMIN_DELIVERY_DIRECTION_SEMANTIC.RETURN : 'warning',
  }
}

/** 송장 정정 가능 여부(BE Delivery.correctTracking·SHIPPING만). */
export function canCorrectTracking(status: AdminDeliveryStatus): boolean {
  return ADMIN_DELIVERY_CORRECTABLE_STATUSES.includes(status)
}

/** 송장 정정 불가 사유 툴팁 문구(가능하면 null). */
export function trackingCorrectionBlockedReason(status: AdminDeliveryStatus): string | null {
  if (canCorrectTracking(status)) return null
  return status === 'DELIVERED'
    ? '배송완료된 배송은 송장을 수정할 수 없습니다.'
    : '송장이 등록된 배송중 상태에서만 수정할 수 있습니다.'
}

/** 클레임 배지 클릭 이동 경로: 클레임 목록에 클레임 id 필터가 없어 주문번호 정확 검색 + 유형으로 좁힌다(BE 무변경·D-184). */
export function toClaimListPath(item: Pick<AdminDeliverySummary, 'orderNo' | 'claimType'>): string {
  const params = new URLSearchParams()
  if (item.orderNo) params.set('keyword', item.orderNo)
  if (item.claimType) params.set('type', item.claimType)
  const query = params.toString()
  return query ? `/admin/orders/claims?${query}` : '/admin/orders/claims'
}
