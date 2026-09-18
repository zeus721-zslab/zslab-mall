import { describe, it, expect } from 'vitest'
import {
  defaultAdminSettlementQuery,
  defaultSettlementMonth,
  hasActiveFilters,
  parseAdminSettlementQuery,
  settlementYearOptions,
  toAdminSettlementApiParams,
  toAdminSettlementRouteQuery,
} from '#layers/admin/app/lib/admin-settlement-query'

// Track 85 FE: 월 기본값(지난달)·URL query ↔ 화면 상태 ↔ BE 파라미터 순수 매핑(year·month 항상·status·keyword·page·size).
const NOW = new Date(2026, 8, 18) // 2026-09-18 → 기본 월 2026-08

describe('defaultSettlementMonth', () => {
  it('지난달을 돌려주고 1월이면 전년 12월', () => {
    expect(defaultSettlementMonth(NOW)).toEqual({ year: 2026, month: 8 })
    expect(defaultSettlementMonth(new Date(2026, 0, 5))).toEqual({ year: 2025, month: 12 })
  })

  it('연도 선택지는 올해부터 과거 3년(내림차순)', () => {
    expect(settlementYearOptions(NOW)).toEqual([2026, 2025, 2024, 2023])
  })
})

describe('parseAdminSettlementQuery', () => {
  it('빈 query → 기본 상태(지난달·상태 없음·page 0·size 20·검색 없음)', () => {
    expect(parseAdminSettlementQuery({}, NOW)).toEqual(defaultAdminSettlementQuery(NOW))
    expect(defaultAdminSettlementQuery(NOW)).toEqual({ year: 2026, month: 8, status: null, keyword: '', page: 0, size: 20 })
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseAdminSettlementQuery({ year: '2025', month: '6', status: 'CONFIRMED', keyword: ' 셀러A ', page: '2', size: '50' }, NOW))
      .toEqual({ year: 2025, month: 6, status: 'CONFIRMED', keyword: '셀러A', page: 2, size: 50 })
  })

  it('잘못된 값은 기본값으로 정규화(범위 밖 월·선택지 밖 연도·미지의 status·음수 page·허용 외 size)·검색어 50자 절단·배열은 첫 값', () => {
    expect(parseAdminSettlementQuery({ year: '2026', month: '13' }, NOW)).toMatchObject({ year: 2026, month: 8 })
    expect(parseAdminSettlementQuery({ year: '2019', month: '5' }, NOW)).toMatchObject({ year: 2026, month: 8 })
    expect(parseAdminSettlementQuery({ year: '2026', status: 'DONE', page: '-1', size: '33' }, NOW)).toEqual(defaultAdminSettlementQuery(NOW))
    expect(parseAdminSettlementQuery({ keyword: 'a'.repeat(60) }, NOW).keyword).toHaveLength(50)
    expect(parseAdminSettlementQuery({ status: ['PAID', 'PENDING'] }, NOW).status).toBe('PAID')
  })

  it('year·month 중 하나만 있으면 둘 다 기본값(반쪽 기간 금지)', () => {
    expect(parseAdminSettlementQuery({ year: '2025' }, NOW)).toMatchObject({ year: 2026, month: 8 })
    expect(parseAdminSettlementQuery({ month: '3' }, NOW)).toMatchObject({ year: 2026, month: 8 })
  })
})

describe('toAdminSettlementRouteQuery', () => {
  it('year·month는 항상 실리고 나머지 기본값 항목은 생략', () => {
    expect(toAdminSettlementRouteQuery(defaultAdminSettlementQuery(NOW))).toEqual({ year: '2026', month: '8' })
    expect(toAdminSettlementRouteQuery({ year: 2025, month: 6, status: 'PAID', keyword: ' 셀러 ', page: 3, size: 100 }))
      .toEqual({ year: '2025', month: '6', status: 'PAID', keyword: '셀러', page: '3', size: '100' })
  })
})

describe('toAdminSettlementApiParams', () => {
  it('year/month/page/size 항상 포함·status·빈 검색어 제외', () => {
    expect(toAdminSettlementApiParams(defaultAdminSettlementQuery(NOW))).toEqual({ year: 2026, month: 8, page: 0, size: 20 })
    expect(toAdminSettlementApiParams({ year: 2025, month: 6, status: 'PENDING', keyword: ' 셀러 ', page: 1, size: 50 }))
      .toEqual({ year: 2025, month: 6, status: 'PENDING', keyword: '셀러', page: 1, size: 50 })
  })
})

describe('hasActiveFilters', () => {
  it('월은 필터가 아니고 상태·검색어만 필터', () => {
    expect(hasActiveFilters({ ...defaultAdminSettlementQuery(NOW), year: 2024, month: 1 })).toBe(false)
    expect(hasActiveFilters({ ...defaultAdminSettlementQuery(NOW), status: 'PAID' })).toBe(true)
    expect(hasActiveFilters({ ...defaultAdminSettlementQuery(NOW), keyword: ' 셀러 ' })).toBe(true)
  })
})
