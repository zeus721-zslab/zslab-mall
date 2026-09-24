import type { Address } from '~/types/address'
import type { OrderStatusSummary, OrderSummary, PagedResponse } from '~/types/order'
import type { Profile } from '~/types/user'
import type { OrderSummaryStage } from '~/lib/utils/order-summary-stages'
import type { orderStatusLabel } from '~/lib/constants/order'
import type { formatDateTime } from '~/lib/utils/datetime'

export interface MypageMenu {
  to: string
  label: string
  description: string
}

/** 홈 섹션 하나의 조회 상태(FE-72). 섹션마다 독립적으로 로딩·실패·재시도한다. */
export interface MypageHomeSection<T> {
  data: T | undefined
  pending: boolean
  error: Error | undefined
  refresh: () => Promise<void>
}

/** 마이페이지 홈 데이터(FE-72). 스킨이 mypageHome을 선언했을 때만 페이지가 채운다. */
export interface MypageHomeVm {
  profile: MypageHomeSection<Profile>
  addresses: MypageHomeSection<Address[]>
  /** 주소 목록의 isDefault 항목. 없으면 null. */
  defaultAddress: Address | null
  summary: MypageHomeSection<OrderStatusSummary>
  /** 요약 응답의 5단계(표시 순서·0건 흐림). 요약이 없으면 빈 배열. */
  summaryStages: OrderSummaryStage[]
  recentOrders: MypageHomeSection<PagedResponse<OrderSummary>>
  orderStatusLabel: typeof orderStatusLabel
  formatDateTime: typeof formatDateTime
}

/** pages/mypage/index.vue → MypageView. home은 mypageHome을 선언한 스킨(renew)에서만 있고 classic은 undefined다. */
export interface MypagePageVm {
  menus: MypageMenu[]
  home: MypageHomeVm | undefined
}
