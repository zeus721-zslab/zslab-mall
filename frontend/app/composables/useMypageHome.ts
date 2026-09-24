import type { Address } from '~/types/address'
import { toOrderSummaryStages, type OrderSummaryStage } from '~/lib/utils/order-summary-stages'

// 홈 "최근 주문"에 보여 줄 주문 수.
const RECENT_ORDERS_SIZE = 3

/**
 * 마이페이지 홈 조회(FE-72·renew MypageView). 페이지가 스킨이 mypageHome을 선언했을 때만 호출한다(classic은 추가 조회 0건).
 * 섹션마다 별도 조회라 SSR에서 병렬로 조회되고 실패도 섹션별로 독립이다 — 뷰는 실패한 섹션만 에러 상태 + 재시도로 보여 준다.
 * 회원명·배송지는 기존 useProfile·useAddresses 조회 함수를 쓰고, 키는 회원 정보·배송지 페이지와 나눈다(같은 키면 데이터를 공유한다).
 */
export function useMypageHome() {
  const { fetchProfile } = useProfile()
  const { listAddresses } = useAddresses()

  const profile = useAsyncData('mypage-home:profile', () => fetchProfile())
  const addresses = useAsyncData('mypage-home:addresses', () => listAddresses())
  const summary = useOrderStatusSummary()
  const recentOrders = useRecentOrders(RECENT_ORDERS_SIZE)

  const defaultAddress = computed<Address | null>(() => addresses.data.value?.find((address) => address.isDefault) ?? null)
  const summaryStages = computed<OrderSummaryStage[]>(() => (summary.data.value ? toOrderSummaryStages(summary.data.value.stages) : []))

  // 세션 만료(401) 판정용. 어느 섹션이든 401이면 페이지가 로그인으로 유도한다.
  const errors = computed(() => [profile.error.value, addresses.error.value, summary.error.value, recentOrders.error.value])

  return {
    profile: { data: profile.data, pending: profile.pending, error: profile.error, refresh: profile.refresh },
    addresses: { data: addresses.data, pending: addresses.pending, error: addresses.error, refresh: addresses.refresh },
    defaultAddress,
    summary: { data: summary.data, pending: summary.pending, error: summary.error, refresh: summary.refresh },
    summaryStages,
    recentOrders: { data: recentOrders.data, pending: recentOrders.pending, error: recentOrders.error, refresh: recentOrders.refresh },
    errors,
  }
}
