import type { CLAIM_TYPE_FILTERS, CLAIM_TYPE_QUERY_VALUES, OrderListTab } from '~/lib/constants/order-tabs'
import type { ClaimSummary } from '~/types/claim'
import type { OrderSummary, OrderSummaryItem, PagedResponse } from '~/types/order'
import type { ClaimType } from '~/lib/constants/claim'
import type { ITEM_CONFIRM_WARNING } from '~/lib/constants/order'
import type {
  CLAIM_REASON_LABELS,
  claimableTypes,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  orderItemStatusLabel,
  refundStatusLabel,
} from '~/lib/constants/claim'
import type { canResumePayment } from '~/lib/utils/payment-resume'
import type { toActiveClaimBadges } from '~/lib/utils/active-claim-badge'
import type { formatDateTime } from '~/lib/utils/datetime'
import type { ItemConfirmNotice } from '~/skins/contracts/order-detail'

/** pages/orders/index.vue → OrdersView. tab·page는 URL이 SoT이며 이동은 moveTo로만 한다. */
export interface OrdersPageVm {
  ORDER_LIST_TABS: OrderListTab[]
  ORDER_LIST_TAB_LABELS: Record<OrderListTab, string>
  tab: OrderListTab
  page: number
  moveTo: (nextTab: OrderListTab, nextPage: number) => void
  isOrderTab: boolean
  orders: PagedResponse<OrderSummary> | undefined
  claims: PagedResponse<ClaimSummary> | undefined
  pending: boolean
  error: Error | undefined
  errorMessage: string
  retry: () => void
  isEmpty: boolean
  emptyMessage: string
  hasNext: boolean
  /** 목록 품목 행의 클레임 진입(FE-73·주문 상세와 같은 이동 경로). */
  goClaim: (item: OrderSummaryItem, type: ClaimType) => void
  /** 목록 품목 행의 구매확정 확인 모달 대상(null = 닫힘·FE-73). */
  confirmTarget: { orderId: string; item: OrderSummaryItem } | null
  confirming: boolean
  confirmNotice: ItemConfirmNotice | null
  openConfirm: (order: OrderSummary, item: OrderSummaryItem) => void
  cancelConfirm: () => void
  submitConfirm: () => Promise<void>
  ITEM_CONFIRM_WARNING: typeof ITEM_CONFIRM_WARNING
  orderItemStatusLabel: typeof orderItemStatusLabel
  claimableTypes: typeof claimableTypes
  claimTypeLabel: typeof claimTypeLabel
  claimStatusLabel: typeof claimStatusLabel
  claimRejectReasonLabel: typeof claimRejectReasonLabel
  refundStatusLabel: typeof refundStatusLabel
  CLAIM_REASON_LABELS: typeof CLAIM_REASON_LABELS
  canResumePayment: typeof canResumePayment
  toActiveClaimBadges: typeof toActiveClaimBadges
  formatDateTime: typeof formatDateTime
  /** 취소·반품·교환 탭의 유형 필터(null = 전체·FE-73 보완 1). 바꾸기는 moveToClaimType으로만 한다(page 0). */
  claimTypeFilter: ClaimType | null
  CLAIM_TYPE_FILTERS: typeof CLAIM_TYPE_FILTERS
  CLAIM_TYPE_QUERY_VALUES: typeof CLAIM_TYPE_QUERY_VALUES
  moveToClaimType: (type: ClaimType | null) => void
}
