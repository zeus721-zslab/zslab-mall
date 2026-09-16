import type { ClaimRejectReasonCode, ClaimStatus, ClaimType, RefundStatus } from '~/lib/constants/claim'
import type { AdminOrderSort } from '#layers/admin/app/lib/constants/admin-order'

/**
 * 관리자 클레임 API 타입(FE-28·Track 80 D-169 BE 계약). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional.
 * 시각 문자열은 +09:00 오프셋 직렬화(KstOffsetSerializer)이며 formatDateTime(앞 16자 슬라이스)으로만 표시한다.
 */

/** 행 액션(BE availableActions 값·REQUESTED만 APPROVE·REJECT). */
export type AdminClaimAction = 'APPROVE' | 'REJECT'

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
  reasonCode: string
  reasonDetail?: string
  rejectReasonCode?: ClaimRejectReasonCode
  rejectMemo?: string
  refundStatus?: RefundStatus
  availableActions: AdminClaimAction[]
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
  keyword: string
  from: string | null
  to: string | null
  sort: AdminOrderSort
  page: number
  size: number
}

/** BE GET /admin/claims 쿼리 파라미터(null·빈 값은 제외). */
export type AdminClaimApiParams = Record<string, string | number>
