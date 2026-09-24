import { describe, it, expect } from 'vitest'
import {
  CLAIM_TYPE_FILTERS,
  CLAIM_TYPE_QUERY_VALUES,
  DEFAULT_ORDER_LIST_TAB,
  ITEM_STATUS_FILTER_PERIOD_MONTHS,
  ORDER_ITEM_STATUS_FILTERS,
  ORDER_LIST_TABS,
  isLegacyClaimTab,
  parseClaimTypeFilter,
  parseItemStatusFilter,
  parseOrderListPage,
  parseOrderListTab,
  tabOfClaimType,
} from '~/lib/constants/order-tabs'
import { toActiveClaimBadges } from '~/lib/utils/active-claim-badge'

/**
 * Track 101-B(FE-63) → FE-73(2탭 통합) 주문내역 탭의 순수 규칙. URL 쿼리 해석과 카드 배지 접기만 고정한다 — 화면 조립은 대상이 아니다.
 */
describe('parseOrderListTab — ?tab= 해석', () => {
  it('탭은 전체 주문 → 취소·반품·교환 2개이고 그대로 돌려준다', () => {
    expect(ORDER_LIST_TABS).toEqual(['order', 'claim'])
    for (const tab of ORDER_LIST_TABS) {
      expect(parseOrderListTab(tab)).toBe(tab)
    }
  })

  it('통합 전 유형 탭(cancel·return·exchange)은 claim 탭으로 해석한다(링크 호환)', () => {
    expect(parseOrderListTab('cancel')).toBe('claim')
    expect(parseOrderListTab('return')).toBe('claim')
    expect(parseOrderListTab('exchange')).toBe('claim')
  })

  it('허용값 밖·미지정·배열은 기본 탭(주문)으로 떨어진다', () => {
    expect(parseOrderListTab('claims')).toBe(DEFAULT_ORDER_LIST_TAB)
    expect(parseOrderListTab('CANCEL')).toBe(DEFAULT_ORDER_LIST_TAB)
    expect(parseOrderListTab(undefined)).toBe(DEFAULT_ORDER_LIST_TAB)
    expect(parseOrderListTab(['cancel'])).toBe(DEFAULT_ORDER_LIST_TAB)
  })
})

describe('parseOrderListPage — ?page= 해석', () => {
  it('0 이상 정수 문자열만 받는다', () => {
    expect(parseOrderListPage('0')).toBe(0)
    expect(parseOrderListPage('3')).toBe(3)
  })

  it('음수·소수·비숫자·미지정은 0', () => {
    expect(parseOrderListPage('-1')).toBe(0)
    expect(parseOrderListPage('1.5')).toBe(0)
    expect(parseOrderListPage('two')).toBe(0)
    expect(parseOrderListPage(undefined)).toBe(0)
  })
})

describe('ClaimType → 탭', () => {
  it('클레임 유형과 무관하게 claim 탭(상세 복귀·진행 클레임 배지)', () => {
    expect(tabOfClaimType('CANCEL')).toBe('claim')
    expect(tabOfClaimType('RETURN')).toBe('claim')
    expect(tabOfClaimType('EXCHANGE')).toBe('claim')
  })
})

describe('toActiveClaimBadges — 카드 배지 접기(2종까지·초과 시 외 N)', () => {
  it('진행 중 클레임이 없으면 빈 배열(배지 미표시)', () => {
    expect(toActiveClaimBadges([])).toEqual([])
    expect(toActiveClaimBadges(undefined)).toEqual([])
    expect(toActiveClaimBadges(null)).toEqual([])
  })

  it('건수 0 항목은 버린다', () => {
    expect(toActiveClaimBadges([{ claimType: 'CANCEL', count: 0 }])).toEqual([])
  })

  it('1~2종은 유형 라벨 + 건수로 그대로 나온다', () => {
    expect(toActiveClaimBadges([
      { claimType: 'CANCEL', count: 1 },
      { claimType: 'RETURN', count: 2 },
    ])).toEqual([
      { label: '취소 1', tab: 'claim', claimType: 'CANCEL' },
      { label: '반품 2', tab: 'claim', claimType: 'RETURN' },
    ])
  })

  it('3종이면 앞 2종 + "외 1"(요약 배지는 이동 대상·유형이 없어 tab·claimType=null)', () => {
    expect(toActiveClaimBadges([
      { claimType: 'CANCEL', count: 1 },
      { claimType: 'RETURN', count: 1 },
      { claimType: 'EXCHANGE', count: 3 },
    ])).toEqual([
      { label: '취소 1', tab: 'claim', claimType: 'CANCEL' },
      { label: '반품 1', tab: 'claim', claimType: 'RETURN' },
      { label: '외 1', tab: null, claimType: null },
    ])
  })
})

describe('parseClaimTypeFilter — 취소·반품·교환 탭 유형 필터(FE-73 보완 1)', () => {
  it('?type= 허용값은 해당 유형', () => {
    expect(parseClaimTypeFilter('cancel', 'claim')).toBe('CANCEL')
    expect(parseClaimTypeFilter('return', 'claim')).toBe('RETURN')
    expect(parseClaimTypeFilter('exchange', 'claim')).toBe('EXCHANGE')
  })

  it('옛 ?tab=cancel|return|exchange는 claim 탭 + 해당 유형', () => {
    for (const [legacyTab, claimType] of [['cancel', 'CANCEL'], ['return', 'RETURN'], ['exchange', 'EXCHANGE']] as const) {
      expect(isLegacyClaimTab(legacyTab)).toBe(true)
      expect(parseOrderListTab(legacyTab)).toBe('claim')
      expect(parseClaimTypeFilter(undefined, legacyTab)).toBe(claimType)
    }
    expect(isLegacyClaimTab('claim')).toBe(false)
  })

  it('허용값 밖·미지정·배열·프로토타입 키 type은 전체(null)', () => {
    expect(parseClaimTypeFilter(undefined, 'claim')).toBeNull()
    expect(parseClaimTypeFilter('refund', 'claim')).toBeNull()
    expect(parseClaimTypeFilter('RETURN', 'claim')).toBeNull()
    expect(parseClaimTypeFilter(['return'], 'claim')).toBeNull()
    expect(parseClaimTypeFilter('toString', 'claim')).toBeNull()
  })

  it('?type= 허용값이 옛 탭 값보다 우선한다', () => {
    expect(parseClaimTypeFilter('exchange', 'return')).toBe('EXCHANGE')
  })

  it('칩 순서는 전체 · 취소 · 반품 · 교환이고 URL 값은 소문자 유형', () => {
    expect(CLAIM_TYPE_FILTERS).toEqual([null, 'CANCEL', 'RETURN', 'EXCHANGE'])
    expect(CLAIM_TYPE_QUERY_VALUES).toEqual({ CANCEL: 'cancel', RETURN: 'return', EXCHANGE: 'exchange' })
  })
})

// FE-80·D-224: 전체 주문 탭의 품목 상태 필터. BE가 받는 5값(요약 단계 품목 상태)만 통과시키고 나머지는 필터 없음으로 무시한다(BE 400 방지).
describe('parseItemStatusFilter — ?itemStatus= 해석', () => {
  it('허용 5값(대문자)은 그대로 · 순서는 주문 흐름', () => {
    expect(ORDER_ITEM_STATUS_FILTERS).toEqual(['PAID', 'PREPARING', 'SHIPPING', 'DELIVERED', 'CONFIRMED'])
    for (const value of ORDER_ITEM_STATUS_FILTERS) expect(parseItemStatusFilter(value)).toBe(value)
  })

  it('단계 밖 품목 상태·소문자·빈 값·배열·미지정은 null', () => {
    expect(parseItemStatusFilter('ORDERED')).toBeNull()
    expect(parseItemStatusFilter('RETURN_REQUESTED')).toBeNull()
    expect(parseItemStatusFilter('delivered')).toBeNull()
    expect(parseItemStatusFilter('')).toBeNull()
    expect(parseItemStatusFilter(['DELIVERED'])).toBeNull()
    expect(parseItemStatusFilter(undefined)).toBeNull()
  })

  it('필터 기간은 BE 요약 기간과 같은 3개월', () => {
    expect(ITEM_STATUS_FILTER_PERIOD_MONTHS).toBe(3)
  })
})
