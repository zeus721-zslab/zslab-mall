import type { OrderListTab } from '~/lib/constants/order-tabs'
import type { ClaimSummary } from '~/types/claim'
import type { OrderSummary, PagedResponse } from '~/types/order'

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
}
