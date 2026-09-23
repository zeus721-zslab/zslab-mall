import type { ClaimInspectionResult, ClaimRejectReasonCode, ClaimStatus, ClaimType, RefundStatus } from '~/lib/constants/claim'
import type { ClaimShipment } from '~/types/claim'
import type { AdminDeliveryCarrier } from '#layers/admin/app/lib/constants/admin-order'
import type { AdminOrderSort } from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 클레임 API 타입(FE-28·Track 80 D-169 BE 계약). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 +09:00 오프셋 직렬화(KstOffsetSerializer)이며 formatDateTime(앞 16자 슬라이스)으로만 표시한다.
 */

/**
 * 행 액션(BE availableActions 값). REQUESTED는 APPROVE·REJECT, RETURN·EXCHANGE APPROVED는 회수 송장 있고 미회수면 CONFIRM_PICKUP·회수 후 미검수면
 * INSPECT(Track 81-A D-170). EXCHANGE는 검수 합격 후 OUTBOUND 미등록이면 REGISTER_EXCHANGE_SHIPMENT·배송중이면 MARK_EXCHANGE_DELIVERED(FE-30·D-177).
 */
export type AdminClaimAction =
  | 'APPROVE'
  | 'REJECT'
  | 'CONFIRM_PICKUP'
  | 'INSPECT'
  | 'REGISTER_EXCHANGE_SHIPMENT'
  | 'MARK_EXCHANGE_DELIVERED'
  | 'INITIATE_REFUND'

/**
 * 목록 "필요 액션" 필터 값(BE AdminClaimActionFilter·Track 96-4 D-205·FE-56). FOLLOWUP은 후속 처리 5종 합집합(대시보드 클레임 처리 대기
 * 타일과 같은 조건). APPROVE·REJECT는 status=REQUESTED 필터가 담당하므로 없다.
 */
export type AdminClaimActionFilter = Exclude<AdminClaimAction, 'APPROVE' | 'REJECT'> | 'FOLLOWUP'

/** 목록 행(BE AdminClaimSummaryResponse). 주문·품목·구매자 미존재 시 해당 필드는 생략된다. */
export interface AdminClaimSummary {
  claimId: string
  type: ClaimType
  status: ClaimStatus
  requestedAt: string
  processedAt?: string
  orderId?: string
  orderItemId?: string
  orderNo?: string
  buyerName?: string
  buyerEmail?: string
  productName?: string
  optionLabel?: string
  quantity: number
  amount?: number
  /** 품목 잔여 환불 상한(품목 금액 − 기환불액·Track 104-4). 환불 재개시 기본 금액·최댓값. */
  itemRemainingRefundable: number
  reasonCode: string
  reasonDetail?: string
  rejectReasonCode?: ClaimRejectReasonCode
  rejectMemo?: string
  refundStatus?: RefundStatus
  /** 실패 처리한 환불에 PG가 성공을 통지한 사실(Track 104-3a). true면 환불 표기는 실패여도 PG에서 돈이 나갔다. */
  pgRefundSucceeded: boolean
  availableActions: AdminClaimAction[]
  /** 반품 회수 Delivery(구매자 등록·Track 81-A). 없으면 생략. */
  returnShipment?: ClaimShipment
  /** 검수 불합격 재발송 또는 교환품 발송 Delivery(OUTBOUND 최신). 없으면 생략. */
  reshipment?: ClaimShipment
  pickedUpAt?: string
  inspectionResult?: ClaimInspectionResult
  restock?: boolean
  /** 교환 전 원 옵션 라벨(EXCHANGE·승인 스냅샷 우선·FE-30·D-177). 비교환·미해소면 생략. */
  originalOptionLabel?: string
  /** 교환 요청 옵션 라벨(EXCHANGE·FE-30·D-177). 비교환·미해소면 생략. */
  exchangeOptionLabel?: string
  /** 반품 사진 첨부 개수(Track 81-B). */
  attachmentCount: number
}

/** 검수 요청 body(BE ClaimInspectRequest·Track 81-A). PASS는 restock 필수, FAIL은 rejectReasonCode·reshipCarrier·reshipTrackingNo 필수. */
export interface AdminClaimInspectBody {
  result: ClaimInspectionResult
  restock?: boolean
  rejectReasonCode?: ClaimRejectReasonCode
  memo?: string
  reshipCarrier?: AdminDeliveryCarrier
  reshipTrackingNo?: string
}

/** 목록 응답(BE AdminClaimListResponse·PagedResponse 5필드 + pendingCount). pendingCount는 type 필터만 반영한다. */
export interface AdminClaimListResponse {
  items: AdminClaimSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
  pendingCount: number
}

/** 거부 요청 body(BE ClaimRejectRequest·reasonCode 필수·memo ≤500). */
export interface AdminClaimRejectBody {
  reasonCode: ClaimRejectReasonCode
  memo?: string
}

/** 목록 화면 상태 = URL query 단일 소스. type은 유형 탭(null=전체). from/to는 yyyy-MM-dd. */
export interface AdminClaimListQuery {
  type: ClaimType | null
  status: ClaimStatus | null
  /** 최신 환불 상태 필터(Track 89-A·환불 없는 클레임은 어느 값에도 안 걸림). */
  refundStatus: RefundStatus | null
  /** 필요 액션 필터(Track 96-4·null=전체). 허용 외 URL 값은 null로 정규화한다. */
  action: AdminClaimActionFilter | null
  keyword: string
  from: string | null
  to: string | null
  sort: AdminOrderSort
  page: number
  size: number
}

/** 수동 환불 개시 요청·응답(BE AdminRefundController initiate-refund·Track 89-A). */
export interface AdminRefundInitiateBody {
  amount: number
}

export interface AdminRefundInitiateResponse {
  refundPublicId: string
  claimPublicId: string
  status: RefundStatus
  amount: number
  pgRefundId?: string
}

/** BE GET /admin/claims 쿼리 파라미터(null·빈 값은 제외). */
export type AdminClaimApiParams = Record<string, string | number>
