import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_STATS_PERIOD_QUERY,
  parseAdminStatsPeriodQuery,
  resolveStatsPeriod,
  toAdminStatsPeriodRouteQuery,
  toStatsApiParams,
} from '#layers/admin/app/lib/admin-stats-period-query'
import {
  claimReasonLabel,
  claimReasonRows,
  claimSummaryCards,
  claimTrendChart,
  compareRefundRateSeries,
  donutChart,
  formatHours,
  funnelStages,
  leadTimeCards,
  normalizeOrderStats,
} from '#layers/admin/app/lib/admin-order-stats-view'
import {
  buyerSplitView,
  gradeRows,
  memberSummaryCards,
  normalizeMemberStats,
  signupTrendChart,
  topBuyerRows,
} from '#layers/admin/app/lib/admin-member-stats-view'
import type { AdminClaimSummary, AdminClaimTrendBucket, AdminOrderStatsResponse } from '#layers/admin/app/types/admin-order-stats'
import type { AdminMemberStatsResponse, AdminMemberSummary } from '#layers/admin/app/types/admin-member-stats'

// FE-35 주문·클레임/회원 통계: 기간 URL 매핑·퍼널 도달률/이탈률·소요시간 포맷(24h 경계)·사유 미매핑 원문·탈퇴 톤 반전·이중 축 혼합 계열·후행 0 → null.

const TODAY = new Date(2026, 8, 18)

describe('기간 URL 매핑(축 없음)', () => {
  it('기본값은 URL에서 생략되고 잘못된 값은 기본값으로 정규화된다', () => {
    expect(toAdminStatsPeriodRouteQuery(DEFAULT_ADMIN_STATS_PERIOD_QUERY)).toEqual({})
    const parsed = parseAdminStatsPeriodQuery({ preset: '7d', unit: 'HOUR', compare: 'PREVIOUS', axis: 'SELLER' })
    expect(parsed).toEqual({ preset: '7d', from: null, to: null, unit: 'DAY', compare: 'PREVIOUS' })
    expect(toAdminStatsPeriodRouteQuery(parsed)).toEqual({ preset: '7d', compare: 'PREVIOUS' })
  })

  it('custom은 from·to가 모두 있을 때만 성립하고 API 파라미터는 from·to·unit·compare만 담는다', () => {
    expect(parseAdminStatsPeriodQuery({ preset: 'custom', from: '2026-09-01' }).preset).toBe('30d')
    const custom = parseAdminStatsPeriodQuery({ preset: 'custom', from: '2026-09-01', to: '2026-09-10', unit: 'WEEK' })
    const period = resolveStatsPeriod(custom, TODAY)
    expect(period).toEqual({ from: '2026-09-01', to: '2026-09-10' })
    expect(toStatsApiParams(custom, period!)).toEqual({ from: '2026-09-01', to: '2026-09-10', unit: 'WEEK', compare: 'NONE' })
    expect(resolveStatsPeriod({ ...DEFAULT_ADMIN_STATS_PERIOD_QUERY, preset: '7d' }, TODAY)).toEqual({ from: '2026-09-12', to: '2026-09-18' })
  })
})

describe('퍼널', () => {
  it('도달률은 결제 대비·이탈률은 직전 대비, 첫 단계는 100%/이탈 없음', () => {
    const stages = funnelStages({ paidItems: 200, shippedItems: 150, deliveredItems: 120, confirmedItems: 90, cancelledItems: 30, returnedItems: 10 })
    expect(stages.map((stage) => stage.label)).toEqual(['결제', '발송', '배송완료', '구매확정'])
    expect(stages[0]).toMatchObject({ count: 200, reachRate: 100, dropRate: null })
    expect(stages[1]).toMatchObject({ count: 150, reachRate: 75, dropRate: 25 })
    expect(stages[2]!.reachRate).toBe(60)
    expect(stages[2]!.dropRate).toBeCloseTo(20)
    expect(stages[3]!.dropRate).toBe(25)
  })

  it('결제 0이면 전 단계 0·이탈률 null, null 퍼널도 0으로 그린다', () => {
    const stages = funnelStages({ paidItems: 0, shippedItems: 0, deliveredItems: 0, confirmedItems: 0, cancelledItems: 0, returnedItems: 0 })
    expect(stages.every((stage) => stage.reachRate === 0 && stage.dropRate === null)).toBe(true)
    expect(funnelStages(null)[0]!.count).toBe(0)
  })
})

describe('소요시간 포맷', () => {
  it('24h 미만은 시간, 이상은 "N일 M시간", 정각·반올림 경계', () => {
    expect(formatHours(0)).toBe('0.0시간')
    expect(formatHours(23.96)).toBe('24.0시간')
    expect(formatHours(24)).toBe('1일')
    expect(formatHours(40.14)).toBe('1일 16시간')
    expect(formatHours(47.6)).toBe('2일')
    expect(formatHours(84.8)).toBe('3일 13시간')
  })

  it('표본 0(null)이면 "데이터 없음"·중앙값 "—"·empty', () => {
    const cards = leadTimeCards({ paidToShipped: { avgHours: 36.25, medianHours: 36, count: 4 }, shippedToDelivered: null, claimRequestedToClosed: null })
    expect(cards[0]).toMatchObject({ average: '1일 12시간', median: '1일 12시간', caption: '표본 4건 · 종결 시각 기준', empty: false })
    expect(cards[1]).toMatchObject({ average: '데이터 없음', median: '—', empty: true })
    expect(leadTimeCards(null).every((card) => card.empty)).toBe(true)
  })
})

describe('클레임 요약·추이', () => {
  const summary: AdminClaimSummary = { claimCount: 4, claimRate: 80, refundAmount: 17000, refundRate: 38.64, refundCount: 2, paidItemCount: 5 }
  const compare: AdminClaimSummary = { claimCount: 2, claimRate: 50, refundAmount: 20000, refundRate: 40, refundCount: 1, paidItemCount: 4 }

  it('요약 카드 4장은 전부 증가가 부정(빨강)·비교 없으면 "—"', () => {
    const cards = claimSummaryCards(summary, compare)
    expect(cards.map((card) => card.key)).toEqual(['claimCount', 'claimRate', 'refundAmount', 'refundRate'])
    expect(cards[0]).toMatchObject({ value: '4건', compareValue: '2건', rateText: '+100.0%', rateClass: 'adm-chip adm-chip--danger' })
    expect(cards[2]).toMatchObject({ value: '17,000원', rateText: '-15.0%', rateClass: 'adm-chip adm-chip--success' })
    expect(claimSummaryCards(summary, null)[1]).toMatchObject({ value: '80.00%', compareValue: null, rateText: '—', rateClass: 'adm-chip adm-chip--neutral' })
  })

  const bucket = (bucketLabel: string, claimCount: number, refundRate: number, refundAmount = refundRate * 100, refundCount = refundAmount > 0 ? 1 : 0): AdminClaimTrendBucket =>
    ({ bucketKey: bucketLabel, bucketLabel, claimCount, claimRate: claimCount * 10, refundAmount, refundRate, refundCount })

  it('비교 환불률 후행 0 구간만 null·중간 0 유지·길이 맞춤', () => {
    const compareTrend = [bucket('a', 1, 5), bucket('b', 0, 0), bucket('c', 2, 3), bucket('d', 0, 0), bucket('e', 0, 0)]
    expect(compareRefundRateSeries(compareTrend, 5)).toEqual([5, 0, 3, null, null])
    expect(compareRefundRateSeries(compareTrend.slice(0, 2), 4)).toEqual([5, null, null, null])
    expect(compareRefundRateSeries(null, 3)).toBeNull()
  })

  it('추이 차트는 클레임률·환불률 2계열 + 비교 시 점선 1계열', () => {
    const trend = [bucket('2026-09-01', 1, 5), bucket('2026-09-02', 2, 6)]
    const plain = claimTrendChart(trend, null)
    expect(plain.series.map((series) => series.name)).toEqual(['클레임률', '환불률'])
    expect(plain.series[0]!.data).toEqual([10, 20])
    const withCompare = claimTrendChart(trend, [bucket('x', 1, 4), bucket('y', 0, 0)])
    expect(withCompare.series[2]).toEqual({ name: '비교 환불률', data: [4, null] })
    expect(withCompare.options.stroke?.dashArray).toEqual([0, 0, 5])
  })

  it('정규화: 생략된 비교·소요시간은 null', () => {
    const response = { funnel: { paidItems: 1, shippedItems: 1, deliveredItems: 0, confirmedItems: 0, cancelledItems: 0, returnedItems: 0 }, leadTime: {}, claimSummary: summary, claimTrend: [], claimByType: [], claimByReason: [] } as AdminOrderStatsResponse
    const normalized = normalizeOrderStats(response)
    expect(normalized.compareClaimSummary).toBeNull()
    expect(normalized.compareClaimTrend).toBeNull()
    expect(normalized.leadTime).toEqual({ paidToShipped: null, shippedToDelivered: null, claimRequestedToClosed: null })
  })
})

describe('분포·도넛', () => {
  it('사유 라벨은 enum 매핑, 미매핑 코드는 원문 그대로', () => {
    expect(claimReasonLabel('BUYER_CHANGED_MIND')).toBe('단순 변심')
    expect(claimReasonLabel('LEGACY_REASON')).toBe('LEGACY_REASON')
    const rows = claimReasonRows([{ reasonCode: 'PRODUCT_DEFECT', count: 3, share: 75 }, { reasonCode: 'X_UNKNOWN', count: 1, share: 25 }])
    expect(rows.map((row) => row.label)).toEqual(['상품 불량', 'X_UNKNOWN'])
  })

  it('도넛은 합 0이면 empty·라벨/계열 순서 유지', () => {
    const chart = donutChart([{ label: '취소', count: 7 }, { label: '반품', count: 5 }], '건')
    expect(chart.series).toEqual([7, 5])
    expect(chart.options.labels).toEqual(['취소', '반품'])
    expect(chart.empty).toBe(false)
    expect(donutChart([{ label: '취소', count: 0 }], '건').empty).toBe(true)
  })
})

describe('회원 요약·분리·상위', () => {
  const summary: AdminMemberSummary = { newCount: 2, withdrawnCount: 2, activeTotal: 3, repurchaseRate: 50, buyerCount: 2, repeatBuyerCount: 1 }
  const compare: AdminMemberSummary = { newCount: 1, withdrawnCount: 1, activeTotal: 2, repurchaseRate: 100, buyerCount: 1, repeatBuyerCount: 1 }

  it('탈퇴 카드만 톤 반전(증가 = 빨강), 나머지는 증가 = 녹색', () => {
    const cards = memberSummaryCards(summary, compare)
    expect(cards.map((card) => card.key)).toEqual(['newCount', 'withdrawnCount', 'activeTotal', 'repurchaseRate', 'buyerCount', 'repeatBuyerCount'])
    expect(cards[0]).toMatchObject({ value: '2명', rateText: '+100.0%', rateClass: 'adm-chip adm-chip--success' })
    expect(cards[1]).toMatchObject({ value: '2명', rateText: '+100.0%', rateClass: 'adm-chip adm-chip--danger' })
    expect(cards[3]).toMatchObject({ value: '50.00%', rateText: '-50.0%', rateClass: 'adm-chip adm-chip--danger' })
    expect(memberSummaryCards(null, null)[2]).toMatchObject({ value: '—', rateText: '—' })
  })

  it('1회 vs 재구매 매출 비중·합 0이면 empty', () => {
    const view = buyerSplitView({ firstTimeBuyerCount: 1, firstTimeRevenue: 9000, repeatBuyerCount: 1, repeatRevenue: 35000 })
    expect(view.firstTime.revenueShare).toBeCloseTo(20.4545, 3)
    expect(view.repeat.revenueShare).toBeCloseTo(79.5455, 3)
    expect(view.empty).toBe(false)
    expect(buyerSplitView(null)).toMatchObject({ empty: true, firstTime: { revenueShare: 0 } })
  })

  it('등급 행 라벨·상위 회원 null 이름/이메일 "—"·publicId 없으면 이동 불가', () => {
    expect(gradeRows([{ gradeCode: 'GOLD', memberCount: 1, share: 50, revenue: 35000, revenueShare: 79.55 }])[0]).toMatchObject({ key: 'GOLD', label: '골드', count: 1, revenue: 35000 })
    const rows = topBuyerRows([{ userPublicId: 'usr_1', name: '김데모', email: 'a@b.c', orderCount: 3, revenue: 35000 }, { orderCount: 1, revenue: 9000 }])
    expect(rows[0]).toMatchObject({ id: 'usr_1', name: '김데모', revenue: '35,000원', navigable: true })
    expect(rows[1]).toMatchObject({ id: 'row-1', name: '—', email: '—', navigable: false })
  })
})

describe('가입 추이 혼합 차트', () => {
  it('신규 가입은 column·활성 누적은 line, y축 2개(우측 opposite)·계열 순서와 축 순서 일치', () => {
    const chart = signupTrendChart([
      { bucketKey: '2026-01', bucketLabel: '2026-01', newCount: 13, activeCumulative: 13 },
      { bucketKey: '2026-02', bucketLabel: '2026-02', newCount: 0, activeCumulative: 12 },
    ])
    expect(chart.series).toEqual([
      { name: '신규 가입', type: 'column', data: [13, 0] },
      { name: '활성 누적', type: 'line', data: [13, 12] },
    ])
    const yaxis = chart.options.yaxis as { seriesName?: string; opposite?: boolean }[]
    expect(yaxis).toHaveLength(2)
    expect(yaxis[0]!.seriesName).toBe('신규 가입')
    expect(yaxis[1]).toMatchObject({ seriesName: '활성 누적', opposite: true })
    expect(chart.options.stroke?.width).toEqual([0, 2])
  })

  it('정규화: 생략된 비교는 null', () => {
    const response = { summary: { newCount: 0, withdrawnCount: 0, activeTotal: 0, repurchaseRate: 0, buyerCount: 0, repeatBuyerCount: 0 }, signupTrend: [], gradeDistribution: [], buyerSplit: { firstTimeBuyerCount: 0, firstTimeRevenue: 0, repeatBuyerCount: 0, repeatRevenue: 0 }, topBuyers: [] } as AdminMemberStatsResponse
    const normalized = normalizeMemberStats(response)
    expect(normalized.compareSummary).toBeNull()
    expect(normalized.compareSignupTrend).toBeNull()
  })
})
