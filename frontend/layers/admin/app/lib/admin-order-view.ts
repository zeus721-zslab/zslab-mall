import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import type {
  AdminFieldError,
  AdminOrderCancelResponse,
  AdminOrderClaim,
  AdminOrderDetail,
  AdminOrderItem,
} from '#layers/admin/app/types/admin-order'
import { claimableTypes } from '~/lib/constants/claim'
import { refundStatusChip } from '#layers/admin/app/lib/admin-claim-view'
import { orderStatusLabel } from '~/lib/constants/order'
import {
  ADMIN_DELIVERY_STATUS_LABEL,
  ADMIN_ORDER_CANCEL_DETAIL_MAX,
  ADMIN_ORDER_TRACKING_NO_MAX,
  type AdminDeliveryStatus,
} from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 주문 화면 표시·검증 순수 함수(FE-27·vitest 대상). 대상 품목 판정은 BE 액션 규칙(AdminOrderQueryService.actions·
 * OrderItemStatus.canTransitionTo)과 1:1이며, 폼 검증은 BE Bean Validation과 같은 한도로 400을 예방한다(도메인 검증은 BE 422).
 */

/** 미결제 주문(전체 종료 경로·품목 선택 없음). */
export function isUnpaidOrder(detail: Pick<AdminOrderDetail, 'status'>): boolean {
  return detail.status === 'PENDING_PAYMENT'
}

/** 취소 가능 품목: 사용자 claimableTypes(PAID·PREPARING → CANCEL)와 동일 판정. */
export function cancellableItems(items: AdminOrderItem[]): AdminOrderItem[] {
  return items.filter((item) => claimableTypes(item.status).includes('CANCEL'))
}

/** 송장 등록 대상: 품목 PAID(BE prepare-shipment 422 규칙·PREPARING은 이미 송장 있음). */
export function shippableItems(items: AdminOrderItem[]): AdminOrderItem[] {
  return items.filter((item) => item.status === 'PAID')
}

/** 배송완료 대상: 최신 배송이 SHIPPING인 품목. */
export function deliverableItems(items: AdminOrderItem[]): AdminOrderItem[] {
  return items.filter((item) => item.delivery?.status === 'SHIPPING')
}

/**
 * 목록 "주문 · 배송" 셀의 배송 chip 표시 여부: 배송이 있고 배송상태 라벨이 주문상태 라벨과 다를 때만(같으면 "배송중·배송중" 중복).
 * 주문 SHIPPING·배송 SHIPPING → 숨김 / 주문 PARTIAL_CANCEL·배송 SHIPPING → 표시 / 배송 없음 → 숨김.
 */
export function showDeliveryChip(orderStatus: string, deliveryStatus: AdminDeliveryStatus | undefined | null): boolean {
  if (!deliveryStatus) return false
  return ADMIN_DELIVERY_STATUS_LABEL[deliveryStatus] !== orderStatusLabel(orderStatus)
}

/** 셀러 컬럼 표기: 없음 '—' / 1곳 이름 / 2곳↑ "첫 이름 외 N". null 원소(셀러 미존재)는 제외. */
export function sellerNamesLabel(names: (string | null)[]): string {
  const known = names.filter((name): name is string => typeof name === 'string' && name !== '')
  if (known.length === 0) return '—'
  return known.length === 1 ? known[0]! : `${known[0]} 외 ${known.length - 1}`
}

/**
 * 클레임 행 환불 표기. BE refundStatus(FE-28·Track 80)가 있으면 그 값(PENDING/COMPLETED/FAILED)을 그대로 쓰고, 없으면 취소 클레임의
 * 상태 추론(recon-report-fe-27 §4 β·APPROVED=진행 중·COMPLETED=완료)으로 폴백한다. 취소 외 유형·그 외 상태는 표기하지 않는다(null).
 */
export function claimRefundLabel(claim: Pick<AdminOrderClaim, 'type' | 'status' | 'refundStatus'>): { text: string; semantic: AdminSemantic } | null {
  const fromStatus = refundStatusChip(claim.refundStatus)
  if (fromStatus) return fromStatus
  if (claim.type !== 'CANCEL') return null
  if (claim.status === 'APPROVED') return { text: '환불 진행 중', semantic: 'warning' }
  if (claim.status === 'COMPLETED') return { text: '환불 완료', semantic: 'success' }
  return null
}

export interface CancelFormInput {
  unpaid: boolean
  selectedItemIds: string[]
  reasonCode: string | null
  reasonDetail: string
}

/** 취소 폼 검증(필드 → 메시지). 미결제는 품목 선택을 요구하지 않는다. */
export function validateCancelForm(input: CancelFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.unpaid && input.selectedItemIds.length === 0) errors.items = '취소할 품목을 1개 이상 선택하세요.'
  if (!input.reasonCode) errors.reasonCode = '취소 사유를 선택하세요.'
  if (input.reasonDetail.length > ADMIN_ORDER_CANCEL_DETAIL_MAX) {
    errors.reasonDetail = `상세 사유는 ${ADMIN_ORDER_CANCEL_DETAIL_MAX}자 이하여야 합니다.`
  }
  return errors
}

export interface ShipmentFormInput {
  orderItemId: string | null
  carrier: string | null
  trackingNo: string
}

/** 송장 폼 검증(필드 → 메시지). BE @NotBlank·@Size(100)과 동일 한도. */
export function validateShipmentForm(input: ShipmentFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.orderItemId) errors.orderItemId = '송장을 등록할 품목을 선택하세요.'
  if (!input.carrier) errors.carrier = '택배사를 선택하세요.'
  const trackingNo = input.trackingNo.trim()
  if (trackingNo === '') errors.trackingNo = '송장번호를 입력하세요.'
  else if (trackingNo.length > ADMIN_ORDER_TRACKING_NO_MAX) {
    errors.trackingNo = `송장번호는 ${ADMIN_ORDER_TRACKING_NO_MAX}자 이하여야 합니다.`
  }
  return errors
}

/** ofetch 에러(400 VALIDATION_FAILED)의 fieldErrors → 필드별 첫 메시지. 없으면 빈 객체. */
export function mapFieldErrors(error: unknown): Record<string, string> {
  const fieldErrors = (error as { data?: { fieldErrors?: unknown } } | null)?.data?.fieldErrors
  if (!Array.isArray(fieldErrors)) return {}
  const mapped: Record<string, string> = {}
  for (const entry of fieldErrors as Partial<AdminFieldError>[]) {
    if (typeof entry.field !== 'string' || entry.field in mapped) continue
    // BE는 리스트 원소 검증을 orderItemPublicIds[0] 형태로 보고한다 → 품목 영역 한 곳에 묶는다.
    const field = entry.field.startsWith('orderItemPublicIds') ? 'items' : entry.field
    mapped[field] = typeof entry.message === 'string' && entry.message !== '' ? entry.message : '입력값을 확인해 주세요.'
  }
  return mapped
}

/** 취소 성공 토스트 문구·의미(취소는 결과가 부정적 의미 → danger). */
export function cancelResultMessage(response: AdminOrderCancelResponse): { text: string; semantic: AdminSemantic } {
  if (response.claims.length === 0) return { text: '미결제 주문을 종료했습니다(재고 예약 해제).', semantic: 'danger' }
  return { text: `${response.claims.length}개 품목의 취소를 승인했습니다. 환불이 진행됩니다.`, semantic: 'danger' }
}
