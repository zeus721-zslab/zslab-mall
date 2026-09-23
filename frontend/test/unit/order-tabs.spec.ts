import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ORDER_LIST_TAB,
  ORDER_LIST_TABS,
  claimTypeOfTab,
  parseOrderListPage,
  parseOrderListTab,
  tabOfClaimType,
} from '~/lib/constants/order-tabs'
import { toActiveClaimBadges } from '~/lib/utils/active-claim-badge'

/**
 * Track 101-B(FE-63) 주문내역 탭의 순수 규칙. URL 쿼리 해석과 카드 배지 접기만 고정한다 — 화면 조립은 대상이 아니다.
 */
describe('parseOrderListTab — ?tab= 해석', () => {
  it('허용 탭 4종은 그대로 돌려준다', () => {
    for (const tab of ORDER_LIST_TABS) {
      expect(parseOrderListTab(tab)).toBe(tab)
    }
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

describe('탭 ↔ ClaimType 매핑', () => {
  it('주문 탭은 클레임 조회 대상이 아니다(null)', () => {
    expect(claimTypeOfTab('order')).toBeNull()
  })

  it('클레임 탭 3종은 BE 유형 3값과 1:1', () => {
    expect(claimTypeOfTab('cancel')).toBe('CANCEL')
    expect(claimTypeOfTab('return')).toBe('RETURN')
    expect(claimTypeOfTab('exchange')).toBe('EXCHANGE')
  })

  it('역방향(상세 → 복귀 탭)도 1:1', () => {
    expect(tabOfClaimType('CANCEL')).toBe('cancel')
    expect(tabOfClaimType('RETURN')).toBe('return')
    expect(tabOfClaimType('EXCHANGE')).toBe('exchange')
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
      { label: '취소 1', tab: 'cancel' },
      { label: '반품 2', tab: 'return' },
    ])
  })

  it('3종이면 앞 2종 + "외 1"(요약 배지는 이동 대상이 없어 tab=null)', () => {
    expect(toActiveClaimBadges([
      { claimType: 'CANCEL', count: 1 },
      { claimType: 'RETURN', count: 1 },
      { claimType: 'EXCHANGE', count: 3 },
    ])).toEqual([
      { label: '취소 1', tab: 'cancel' },
      { label: '반품 1', tab: 'return' },
      { label: '외 1', tab: null },
    ])
  })
})
