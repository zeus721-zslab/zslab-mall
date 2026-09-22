import { describe, it, expect } from 'vitest'
import {
  CHANGE_RATE_UNAVAILABLE,
  PENDING_TILES,
  changeChipClass,
  changeRate,
  changeTone,
  dailyOrdersChart,
  dayLabel,
  formatChangeRate,
  isAllZero,
  monthLabel,
  monthlyRevenueChart,
  pendingChipClass,
} from '#layers/admin/app/lib/admin-dashboard-view'
import { formatWon } from '#layers/admin/app/lib/format'
import { ADMIN_DASHBOARD_PATH, ADMIN_ORDERS_PATH, ADMIN_PRODUCTS_PATH, resolveBackPath } from '#layers/admin/app/lib/admin-back-path'

// FE-33 관리자 대시보드: 증감률 계산(0 나눗셈 "—")·배지 톤·처리 대기 링크/톤·차트 라벨/포맷·금액 포맷·대시보드 back 허용.

describe('증감률', () => {
  it('(현재 − 비교) / 비교 × 100', () => {
    expect(changeRate(120, 100)).toBeCloseTo(20)
    expect(changeRate(80, 100)).toBeCloseTo(-20)
    expect(changeRate(100, 100)).toBe(0)
  })

  it('비교값 0·음수·비정상은 null — Infinity·NaN을 만들지 않는다', () => {
    expect(changeRate(50, 0)).toBeNull()
    expect(changeRate(0, 0)).toBeNull()
    expect(changeRate(10, -5)).toBeNull()
    expect(changeRate(Number.NaN, 10)).toBeNull()
    expect(changeRate(10, Number.POSITIVE_INFINITY)).toBeNull()
  })

  it('표기: +부호·소수 1자리·비교 불가 "—"', () => {
    expect(formatChangeRate(17.44)).toBe('+17.4%')
    expect(formatChangeRate(-18.75)).toBe('-18.8%')
    expect(formatChangeRate(0)).toBe('0.0%')
    expect(formatChangeRate(null)).toBe(CHANGE_RATE_UNAVAILABLE)
    expect(CHANGE_RATE_UNAVAILABLE).toBe('—')
  })

  it('톤: 증가 녹색·감소 빨강·0/비교 불가 회색', () => {
    expect(changeTone(5)).toBe('up')
    expect(changeTone(-5)).toBe('down')
    expect(changeTone(0)).toBe('flat')
    expect(changeTone(null)).toBe('flat')
    expect(changeChipClass('up')).toBe('adm-chip adm-chip--success')
    expect(changeChipClass('down')).toBe('adm-chip adm-chip--danger')
    expect(changeChipClass('flat')).toBe('adm-chip adm-chip--neutral')
  })
})

describe('처리 대기', () => {
  it('8칸 순서·링크: 정산·클레임·배송·재고 임박·상품 승인·셀러 승인·클레임 처리 대기·장기 배송중 전부 목록 필터로', () => {
    expect(PENDING_TILES.map((tile) => tile.key)).toEqual(['settlementPending', 'claimRequested', 'deliveryReady', 'lowStock', 'productPending', 'sellerPending', 'claimFollowup', 'longShipping'])
    expect(PENDING_TILES[0]!.to).toBe('/admin/settlements?status=PENDING')
    expect(PENDING_TILES[1]!.to).toBe('/admin/orders/claims?status=REQUESTED')
    expect(PENDING_TILES[2]!.to).toBe('/admin/orders?status=PAID')
    expect(PENDING_TILES[3]!.to).toBe('/admin/products?stockFilter=LOW')
    // Track 96-2(FE-54·C-01): 승인 대기 2칸은 각 목록의 status=PENDING(목록이 URL query로 필터 복원)
    expect(PENDING_TILES[4]!.to).toBe('/admin/products?status=PENDING')
    expect(PENDING_TILES[5]!.to).toBe('/admin/members/sellers?status=PENDING')
    // Track 96-4(FE-56·C-02): 클레임 처리 대기는 클레임 목록 action=FOLLOWUP(BE 카운트와 같은 Specification)
    expect(PENDING_TILES[6]!.to).toBe('/admin/orders/claims?action=FOLLOWUP')
    // Track 99(FE-61·D-210): 장기 배송중은 배송 목록 status=SHIPPING(BE는 발송 후 3일 이상 건수·목록은 배송중 전체라 근사)
    expect(PENDING_TILES[7]!.to).toBe('/admin/orders/deliveries?status=SHIPPING')
  })

  it('톤: 0건 회색, 1건 이상은 정산·클레임·배송 노랑·재고 임박 빨강', () => {
    const [settlement, , , lowStock] = PENDING_TILES
    expect(pendingChipClass(settlement!, 0)).toBe('adm-chip adm-chip--neutral')
    expect(pendingChipClass(settlement!, 3)).toBe('adm-chip adm-chip--warning')
    expect(pendingChipClass(lowStock!, 0)).toBe('adm-chip adm-chip--neutral')
    expect(pendingChipClass(lowStock!, 1)).toBe('adm-chip adm-chip--danger')
    const [, , , , productPending, sellerPending, claimFollowup, longShipping] = PENDING_TILES
    expect(pendingChipClass(productPending!, 1)).toBe('adm-chip adm-chip--warning')
    expect(pendingChipClass(sellerPending!, 0)).toBe('adm-chip adm-chip--neutral')
    expect(pendingChipClass(claimFollowup!, 2)).toBe('adm-chip adm-chip--warning')
    expect(pendingChipClass(longShipping!, 2)).toBe('adm-chip adm-chip--warning')
  })
})

describe('차트', () => {
  const monthly = [
    { yearMonth: '2026-04', revenue: 100, refund: 10, netRevenue: 90, orderCount: 2 },
    { yearMonth: '2026-05', revenue: 0, refund: 0, netRevenue: 0, orderCount: 0 },
  ]
  const daily = [
    { date: '2026-09-17', orderCount: 3, revenue: 300 },
    { date: '2026-09-18', orderCount: 0, revenue: 0 },
  ]

  it('축 라벨: 월 "yyyy.MM"·일 "MM.dd"', () => {
    expect(monthLabel('2026-04')).toBe('2026.04')
    expect(dayLabel('2026-09-18')).toBe('09.18')
  })

  it('월별: 매출·환불 2계열·카테고리·y축/툴팁 원 단위 콤마', () => {
    const spec = monthlyRevenueChart(monthly)
    expect(spec.series).toEqual([
      { name: '매출', data: [100, 0] },
      { name: '환불', data: [10, 0] },
    ])
    expect(spec.options.xaxis?.categories).toEqual(['2026.04', '2026.05'])
    const yaxis = spec.options.yaxis as { labels: { formatter: (value: number) => string } }
    expect(yaxis.labels.formatter(1_500_000)).toBe('1,500,000원')
    const tooltip = spec.options.tooltip as { y: { formatter: (value: number) => string } }
    expect(tooltip.y.formatter(69_900)).toBe('69,900원')
  })

  it('일별: 주문수 1계열·y축 정수 "N건"', () => {
    const spec = dailyOrdersChart(daily)
    expect(spec.series).toEqual([{ name: '주문수', data: [3, 0] }])
    expect(spec.options.xaxis?.categories).toEqual(['09.17', '09.18'])
    const yaxis = spec.options.yaxis as { labels: { formatter: (value: number) => string } }
    expect(yaxis.labels.formatter(2.4)).toBe('2건')
  })

  it('빈 상태 판정: 전부 0이면 true·빈 배열도 true', () => {
    expect(isAllZero([0, 0])).toBe(true)
    expect(isAllZero([])).toBe(true)
    expect(isAllZero([0, 1])).toBe(false)
  })
})

describe('금액 포맷·back 허용', () => {
  it('formatWon: 천단위 콤마 + 원·null/undefined는 —', () => {
    expect(formatWon(1_501_500)).toBe('1,501,500원')
    expect(formatWon(0)).toBe('0원')
    expect(formatWon(null)).toBe('—')
    expect(formatWon(undefined)).toBe('—')
  })

  it('대시보드(/admin)는 주문·상품 상세의 back으로 허용, 그 외 base는 기존대로', () => {
    expect(resolveBackPath(ADMIN_DASHBOARD_PATH, ADMIN_ORDERS_PATH)).toBe('/admin')
    expect(resolveBackPath(ADMIN_DASHBOARD_PATH, ADMIN_PRODUCTS_PATH)).toBe('/admin')
    expect(resolveBackPath('/admin/evil', ADMIN_ORDERS_PATH)).toBe(ADMIN_ORDERS_PATH)
    expect(resolveBackPath(ADMIN_DASHBOARD_PATH, '/admin/members')).toBe('/admin/members')
  })
})
