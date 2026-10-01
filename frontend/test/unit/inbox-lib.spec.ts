import { describe, it, expect } from 'vitest'
import type { InboxItemResponse } from '~/types/inbox'
import {
  INBOX_AUDIENCE_TYPES,
  INBOX_ITEM_TYPES,
  inboxItemTypeLabel,
  isInboxItemType,
  isInboxTab,
  isInboxTypeOf,
} from '~/lib/constants/inbox'
import { DEFAULT_INBOX_QUERY, inboxItemKey, parseInboxQuery, toInboxApiParams, toInboxRouteQuery } from '~/lib/inbox-query'
import {
  formatDuration,
  inboxDeadline,
  inboxEmptyMessage,
  inboxPanelAction,
  inboxTotal,
  msUntilNextKstMidnight,
  nextInboxSelection,
  normalizeInboxItem,
} from '~/lib/inbox-view'
import { INBOX_TARGET_KEYWORD_MAX, inboxTargetRoute } from '~/lib/inbox-target'
import { customSnoozeUntil, inboxSnoozePresets, kstLocalInputMin, toKstIso } from '~/lib/inbox-snooze'

// 운영 인박스 공용 로직(D-248 · FE-101). 시각은 +09:00 고정 문자열로 만들어 실행 환경 시간대와 무관하다.
const at = (iso: string): number => Date.parse(iso)
const MINUTE = 60 * 1000
const HOUR = 60 * MINUTE

function response(overrides: Partial<InboxItemResponse> = {}): InboxItemResponse {
  return { type: 'INQUIRY_UNANSWERED', ref: 'inq_1', title: '배송이 늦어요', subtitle: 'DELIVERY', overdue: false, targetKey: 'INQUIRY', ...overrides }
}

describe('상수(4층위 (4))', () => {
  it('유형 12종 · 역할별 목록(관리자 9 · 셀러 4 · 장기 배송중 공용) · 라벨·가드', () => {
    expect(INBOX_ITEM_TYPES).toHaveLength(12)
    expect(INBOX_AUDIENCE_TYPES.ADMIN).toHaveLength(9)
    expect(INBOX_AUDIENCE_TYPES.SELLER).toEqual(['LONG_SHIPPING', 'DELIVERY_READY', 'QUESTION_UNANSWERED', 'LOW_STOCK'])
    expect(INBOX_AUDIENCE_TYPES.ADMIN).toContain('LONG_SHIPPING')
    expect(inboxItemTypeLabel('CLAIM_FOLLOWUP')).toBe('클레임 후속')
    expect(inboxItemTypeLabel('UNKNOWN')).toBe('UNKNOWN')
    expect(isInboxItemType('LOW_STOCK')).toBe(true)
    expect(isInboxItemType('LOW')).toBe(false)
    expect(isInboxTypeOf('ADMIN', 'DELIVERY_READY')).toBe(false)
    expect(isInboxTypeOf('SELLER', 'DELIVERY_READY')).toBe(true)
    expect(isInboxTab('UPCOMING')).toBe(true)
    expect(isInboxTab('TOMORROW')).toBe(false)
  })
})

describe('쿼리 상태', () => {
  it('기본값은 URL에서 빠지고 · 다른 역할 유형·잘못된 탭은 기본값으로', () => {
    expect(parseInboxQuery({}, 'ADMIN')).toEqual(DEFAULT_INBOX_QUERY)
    expect(parseInboxQuery({ tab: 'NEXT', type: 'DELIVERY_READY' }, 'ADMIN')).toEqual(DEFAULT_INBOX_QUERY)
    expect(parseInboxQuery({ tab: 'UPCOMING', type: 'DELIVERY_READY', selected: 'DELIVERY_READY:oit_1' }, 'SELLER'))
      .toEqual({ tab: 'UPCOMING', type: 'DELIVERY_READY', selected: 'DELIVERY_READY:oit_1' })
    expect(toInboxRouteQuery(DEFAULT_INBOX_QUERY)).toEqual({})
    expect(toInboxRouteQuery({ tab: 'UPCOMING', type: 'LOW_STOCK', selected: 'LOW_STOCK:var_1' }))
      .toEqual({ tab: 'UPCOMING', type: 'LOW_STOCK', selected: 'LOW_STOCK:var_1' })
    expect(toInboxApiParams({ tab: 'TODAY', type: null, selected: 'x' })).toEqual({ tab: 'TODAY' })
    expect(toInboxApiParams({ tab: 'TODAY', type: 'SELLER_REVIEW', selected: null })).toEqual({ tab: 'TODAY', type: 'SELLER_REVIEW' })
  })

  it('선택 키 = 유형:ref(정산·불일치 id가 겹쳐도 유일)', () => {
    expect(inboxItemKey({ type: 'SETTLEMENT_PAYOUT', ref: '12' })).toBe('SETTLEMENT_PAYOUT:12')
    expect(inboxItemKey({ type: 'RECONCILIATION_OPEN', ref: '12' })).toBe('RECONCILIATION_OPEN:12')
  })
})

describe('응답 정규화', () => {
  it('dueAt·baseAt 부재 = 기한 없음(null) · 클레임 후속 ref는 ":" 앞이 이동 키 · 제목 부재는 유형 라벨', () => {
    const lowStock = normalizeInboxItem(response({ type: 'LOW_STOCK', ref: 'var_1', title: undefined, subtitle: undefined, targetKey: 'INVENTORY' }))
    expect(lowStock).toMatchObject({ key: 'LOW_STOCK:var_1', sourceRef: 'var_1', step: null, title: '재고 임박', subtitle: null, dueAt: null, baseAt: null })

    const followup = normalizeInboxItem(response({ type: 'CLAIM_FOLLOWUP', ref: 'clm_ABC:REFUND', dueAt: '2026-10-01T10:00:00+09:00', targetKey: 'CLAIM' }))
    expect(followup).toMatchObject({ key: 'CLAIM_FOLLOWUP:clm_ABC:REFUND', sourceRef: 'clm_ABC', step: 'REFUND', dueAt: '2026-10-01T10:00:00+09:00' })
  })

  it('클레임 접수 행의 유형·제안(D-250)은 그대로 · 다른 유형은 null', () => {
    const claim = normalizeInboxItem(response({ type: 'CLAIM_REQUESTED', ref: 'clm_A', targetKey: 'CLAIM', claimType: 'CANCEL', suggestion: 'APPROVE' }))
    expect(claim).toMatchObject({ claimType: 'CANCEL', suggestion: 'APPROVE' })
    expect(normalizeInboxItem(response())).toMatchObject({ claimType: null, suggestion: null })
  })
})

describe('기한 표시', () => {
  const now = at('2026-10-01T10:30:00+09:00')

  it('경과·임박(3시간 이내)·여유·기한 없음 + 상대 시각', () => {
    expect(inboxDeadline(null, now)).toEqual({ tone: 'none', text: '기한 없음' })
    expect(inboxDeadline('2026-09-29T10:30:00+09:00', now)).toEqual({ tone: 'overdue', text: '2일 지남' })
    expect(inboxDeadline('2026-10-01T10:00:00+09:00', now)).toEqual({ tone: 'overdue', text: '30분 지남' })
    expect(inboxDeadline('2026-10-01T12:30:00+09:00', now)).toEqual({ tone: 'imminent', text: '2시간 남음' })
    expect(inboxDeadline('2026-10-01T13:30:00+09:00', now)).toEqual({ tone: 'imminent', text: '3시간 남음' })
    expect(inboxDeadline('2026-10-01T15:30:00+09:00', now)).toEqual({ tone: 'normal', text: '5시간 남음' })
    expect(inboxDeadline('2026-10-01T10:30:20+09:00', now)).toEqual({ tone: 'imminent', text: '1분 남음' })
    // 같은 순간을 다른 오프셋으로 써도 같은 결과(절대 시각 비교)
    expect(inboxDeadline('2026-10-01T03:30:00Z', now)).toEqual({ tone: 'imminent', text: '2시간 남음' })
  })

  it('단위: 1시간 미만 분 · 하루 미만 시간 · 그 이상 일(내림)', () => {
    expect(formatDuration(59 * MINUTE)).toBe('59분')
    expect(formatDuration(HOUR)).toBe('1시간')
    expect(formatDuration(-(47 * HOUR))).toBe('1일')
  })

  it('다음 KST 자정까지(브라우저 시간대 무관)', () => {
    expect(msUntilNextKstMidnight(at('2026-10-01T23:59:00+09:00'))).toBe(MINUTE)
    expect(msUntilNextKstMidnight(at('2026-10-01T00:00:00+09:00'))).toBe(24 * HOUR)
    // UTC로는 전날 15:30이지만 KST로는 10-01 00:30 → 23시간 30분
    expect(msUntilNextKstMidnight(at('2026-09-30T15:30:00Z'))).toBe(23 * HOUR + 30 * MINUTE)
  })
})

describe('목록', () => {
  it('배지 건수 = counts 합', () => {
    expect(inboxTotal([{ type: 'DELIVERY_READY', count: 3 }, { type: 'LOW_STOCK', count: 2 }])).toBe(5)
    expect(inboxTotal([])).toBe(0)
  })

  it('처리 후 다음 선택: 남아 있으면 그대로 · 뒤 항목 · 없으면 앞 항목 · 비면 null', () => {
    expect(nextInboxSelection(['a', 'b', 'c'], ['a', 'c'], 'b')).toBe('c')
    expect(nextInboxSelection(['a', 'b', 'c'], ['a', 'b'], 'c')).toBe('b')
    expect(nextInboxSelection(['a', 'b', 'c'], ['a', 'b', 'c'], 'b')).toBe('b')
    expect(nextInboxSelection(['a', 'b', 'c'], ['d'], 'b')).toBe('d')
    expect(nextInboxSelection(['a'], [], 'a')).toBeNull()
    expect(nextInboxSelection([], ['x'], null)).toBe('x')
  })

  it('빈 상태 문구: 오늘 / 예정 / 유형 필터', () => {
    expect(inboxEmptyMessage('TODAY', null)).toBe('오늘 처리할 일이 없습니다.')
    expect(inboxEmptyMessage('UPCOMING', null)).toBe('예정된 처리 대기가 없습니다.')
    expect(inboxEmptyMessage('TODAY', 'LOW_STOCK')).toBe('재고 임박 — 오늘 처리할 일이 없습니다.')
  })

  it('패널 처리 가능 유형: 관리자 문의·셀러 심사·클레임 접수(D-250) · 셀러 발송 대기·Q&A · 그 외 이동만', () => {
    expect(inboxPanelAction('ADMIN', 'INQUIRY_UNANSWERED')).toBe('INQUIRY_ANSWER')
    expect(inboxPanelAction('ADMIN', 'SELLER_REVIEW')).toBe('SELLER_STATUS')
    expect(inboxPanelAction('ADMIN', 'CLAIM_REQUESTED')).toBe('CLAIM_DECISION')
    expect(inboxPanelAction('SELLER', 'DELIVERY_READY')).toBe('SHIPMENT')
    expect(inboxPanelAction('SELLER', 'QUESTION_UNANSWERED')).toBe('QUESTION_ANSWER')
    for (const type of ['CLAIM_FOLLOWUP', 'LONG_SHIPPING', 'PRODUCT_APPROVAL', 'SETTLEMENT_CONFIRM', 'SETTLEMENT_PAYOUT', 'RECONCILIATION_OPEN'] as const) {
      expect(inboxPanelAction('ADMIN', type)).toBeNull()
    }
    expect(inboxPanelAction('SELLER', 'LONG_SHIPPING')).toBeNull()
    expect(inboxPanelAction('SELLER', 'LOW_STOCK')).toBeNull()
  })
})

describe('원래 화면 경로', () => {
  const item = (overrides: Partial<InboxItemResponse>) => normalizeInboxItem(response(overrides))

  it('관리자: 클레임 후속은 단계 → action 필터 + 주문번호 · 미지 단계는 FOLLOWUP · 상세 화면 유형은 상세로', () => {
    expect(inboxTargetRoute('ADMIN', item({ type: 'CLAIM_FOLLOWUP', ref: 'clm_A:REFUND', subtitle: 'ORD1', targetKey: 'CLAIM' })))
      .toEqual({ path: '/admin/orders/claims', query: { action: 'INITIATE_REFUND', keyword: 'ORD1' } })
    expect(inboxTargetRoute('ADMIN', item({ type: 'CLAIM_FOLLOWUP', ref: 'clm_A:PICKUP', subtitle: 'ORD1', targetKey: 'CLAIM' })).query.action).toBe('CONFIRM_PICKUP')
    expect(inboxTargetRoute('ADMIN', item({ type: 'CLAIM_FOLLOWUP', ref: 'clm_A:NEW', subtitle: undefined, targetKey: 'CLAIM' })))
      .toEqual({ path: '/admin/orders/claims', query: { action: 'FOLLOWUP' } })
    expect(inboxTargetRoute('ADMIN', item({ type: 'CLAIM_REQUESTED', ref: 'clm_B', subtitle: 'ORD2', targetKey: 'CLAIM' })))
      .toEqual({ path: '/admin/orders/claims', query: { status: 'REQUESTED', keyword: 'ORD2' } })
    expect(inboxTargetRoute('ADMIN', item({ type: 'SELLER_REVIEW', ref: 'slr_1', targetKey: 'SELLER' })).path).toBe('/admin/members/sellers/slr_1')
    expect(inboxTargetRoute('ADMIN', item({ type: 'SETTLEMENT_PAYOUT', ref: '12', targetKey: 'SETTLEMENT' })).path).toBe('/admin/settlements/12')
    expect(inboxTargetRoute('ADMIN', item({ type: 'RECONCILIATION_OPEN', ref: '7', title: 'ITEM_STATE_DRIFT', targetKey: 'RECONCILIATION' })))
      .toEqual({ path: '/admin/orders/reconciliation', query: { status: 'OPEN', type: 'ITEM_STATE_DRIFT' } })
    expect(inboxTargetRoute('ADMIN', item({ type: 'INQUIRY_UNANSWERED' }))).toEqual({ path: '/admin/inquiries', query: {} })
    expect(inboxTargetRoute('ADMIN', item({ type: 'LONG_SHIPPING', ref: 'dlv_1', subtitle: 'ORD3', targetKey: 'DELIVERY' })))
      .toEqual({ path: '/admin/orders/deliveries', query: { status: 'SHIPPING', keyword: 'ORD3' } })
  })

  it('셀러: 발송 대기는 품목 상세 · 재고 임박은 상품명 검색(50자로 자름)', () => {
    expect(inboxTargetRoute('SELLER', item({ type: 'DELIVERY_READY', ref: 'oit_1', targetKey: 'ORDER_ITEM' })).path).toBe('/seller/orders/oit_1')
    expect(inboxTargetRoute('SELLER', item({ type: 'QUESTION_UNANSWERED', ref: 'pqn_1', targetKey: 'PRODUCT_QUESTION' })).path).toBe('/seller/products/questions')
    const longName = '가'.repeat(60)
    const lowStock = inboxTargetRoute('SELLER', item({ type: 'LOW_STOCK', ref: 'var_1', title: longName, targetKey: 'INVENTORY' }))
    expect(lowStock.path).toBe('/seller/products/inventory')
    expect(lowStock.query.keyword).toHaveLength(INBOX_TARGET_KEYWORD_MAX)
    expect(inboxTargetRoute('SELLER', item({ type: 'LONG_SHIPPING', ref: 'dlv_1', subtitle: 'ORD9', targetKey: 'DELIVERY' })))
      .toEqual({ path: '/seller/deliveries', query: { status: 'SHIPPING', keyword: 'ORD9' } })
  })
})

describe('보류 시각(KST)', () => {
  it('오전: 1시간 뒤 · 오늘 18:00 · 내일 09:00', () => {
    const presets = inboxSnoozePresets(at('2026-10-01T10:30:00+09:00'))
    expect(presets.map((preset) => [preset.key, preset.untilAt])).toEqual([
      ['IN_ONE_HOUR', '2026-10-01T11:30:00+09:00'],
      ['TODAY_EVENING', '2026-10-01T18:00:00+09:00'],
      ['TOMORROW_MORNING', '2026-10-02T09:00:00+09:00'],
    ])
  })

  it('18:00 이후면 "오늘 18:00" 숨김 · KST 자정 직후(UTC로는 전날)도 KST 날짜 기준', () => {
    expect(inboxSnoozePresets(at('2026-10-01T18:00:00+09:00')).map((preset) => preset.key)).toEqual(['IN_ONE_HOUR', 'TOMORROW_MORNING'])
    const afterMidnight = inboxSnoozePresets(at('2026-09-30T15:30:00Z')) // KST 10-01 00:30
    expect(afterMidnight.find((preset) => preset.key === 'TODAY_EVENING')?.untilAt).toBe('2026-10-01T18:00:00+09:00')
    expect(afterMidnight.find((preset) => preset.key === 'TOMORROW_MORNING')?.untilAt).toBe('2026-10-02T09:00:00+09:00')
  })

  it('직접 선택: KST로 해석 · 지난 시각·없는 날짜·형식 오류는 null', () => {
    const now = at('2026-10-01T10:30:00+09:00')
    expect(customSnoozeUntil('2026-10-01T12:00', now)).toBe('2026-10-01T12:00:00+09:00')
    expect(customSnoozeUntil('2026-10-01T10:30', now)).toBeNull()
    expect(customSnoozeUntil('2026-02-30T10:00', now)).toBeNull()
    expect(customSnoozeUntil('2026-10-01 12:00', now)).toBeNull()
    expect(kstLocalInputMin(now)).toBe('2026-10-01T10:30')
    expect(toKstIso(at('2026-09-30T15:00:00Z'))).toBe('2026-10-01T00:00:00+09:00')
  })
})
