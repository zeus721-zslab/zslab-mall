/**
 * 주문 카드의 진행 중 클레임 배지 표기 규칙(FE-63·Track 101-B). BE는 유형별 건수를 그대로 내려주고(OrderSummaryResponse.activeClaims),
 * "2종까지 보여주고 초과분은 외 N"으로 접는 것은 화면 규칙이라 FE가 담당한다.
 */

import type { ActiveClaimCount } from '~/types/order'
import { claimTypeLabel, type ClaimType } from '~/lib/constants/claim'
import { tabOfClaimType, type OrderListTab } from '~/lib/constants/order-tabs'

/**
 * 카드에 찍는 배지 1개. tab이 있으면 클레임 탭으로 이동하는 배지이고, 없으면 "외 N" 요약이다.
 * claimType은 유형 배지(renew 색·유형 필터 링크·FE-73 보완 1)용이며 "외 N" 요약은 null이다.
 */
export interface ActiveClaimBadge {
  label: string
  tab: OrderListTab | null
  claimType: ClaimType | null
}

/** 한 카드에 나열하는 유형 배지 수 상한. 초과분은 "외 N" 배지 하나로 접는다. */
const MAX_VISIBLE_TYPES = 2

/**
 * 유형별 건수를 카드 배지 목록으로 접는다. 건수가 0 이하인 항목은 버리고, 남은 유형이 2종을 넘으면
 * 앞 2종 + "외 N"(N = 남은 유형 수)으로 만든다. 진행 중 클레임이 없으면 빈 배열이라 카드는 배지를 그리지 않는다.
 */
export function toActiveClaimBadges(activeClaims: ActiveClaimCount[] | undefined | null): ActiveClaimBadge[] {
  const counted = (activeClaims ?? []).filter((entry) => entry.count > 0)
  if (counted.length === 0) return []

  const visible = counted.slice(0, MAX_VISIBLE_TYPES).map((entry) => ({
    label: `${claimTypeLabel(entry.claimType)} ${entry.count}`,
    tab: tabOfClaimType(entry.claimType),
    claimType: entry.claimType,
  }))
  const hidden = counted.length - visible.length
  return hidden > 0 ? [...visible, { label: `외 ${hidden}`, tab: null, claimType: null }] : visible
}
