import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_MEMBER_QUERY,
  hasActiveFilters,
  memberRowNumber,
  parseAdminMemberQuery,
  toAdminMemberApiParams,
  toAdminMemberRouteQuery,
} from '#layers/admin/app/lib/admin-member-query'

// Track 84 FE: URL query ↔ 화면 상태 ↔ BE 파라미터 순수 매핑(keyword·sort·page·size·status 주입) + 행 번호.
describe('parseAdminMemberQuery', () => {
  it('빈 query → 기본 상태(LATEST·page 0·size 20·검색 없음)', () => {
    expect(parseAdminMemberQuery({})).toEqual(DEFAULT_ADMIN_MEMBER_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseAdminMemberQuery({ keyword: ' 홍길동 ', sort: 'OLDEST', page: '2', size: '50' }))
      .toEqual({ keyword: '홍길동', sort: 'OLDEST', page: 2, size: 50 })
  })

  it('잘못된 값은 기본값으로 정규화(미지의 sort·음수 page·허용 외 size)·검색어 50자 절단·배열은 첫 값', () => {
    expect(parseAdminMemberQuery({ sort: 'RANDOM', page: '-1', size: '33' })).toEqual(DEFAULT_ADMIN_MEMBER_QUERY)
    expect(parseAdminMemberQuery({ keyword: 'a'.repeat(60) }).keyword).toHaveLength(50)
    expect(parseAdminMemberQuery({ sort: ['OLDEST', 'LATEST'] }).sort).toBe('OLDEST')
  })
})

describe('toAdminMemberRouteQuery', () => {
  it('기본값 항목은 생략·나머지는 문자열', () => {
    expect(toAdminMemberRouteQuery(DEFAULT_ADMIN_MEMBER_QUERY)).toEqual({})
    expect(toAdminMemberRouteQuery({ keyword: ' 010 ', sort: 'OLDEST', page: 3, size: 100 }))
      .toEqual({ keyword: '010', sort: 'OLDEST', page: '3', size: '100' })
  })
})

describe('toAdminMemberApiParams', () => {
  it('status는 페이지 주입값·sort/page/size 항상 포함·빈 검색어 제외', () => {
    expect(toAdminMemberApiParams(DEFAULT_ADMIN_MEMBER_QUERY, 'ACTIVE')).toEqual({ status: 'ACTIVE', sort: 'LATEST', page: 0, size: 20 })
    expect(toAdminMemberApiParams({ keyword: ' 홍 ', sort: 'OLDEST', page: 1, size: 50 }, 'WITHDRAWN'))
      .toEqual({ status: 'WITHDRAWN', sort: 'OLDEST', page: 1, size: 50, keyword: '홍' })
  })
})

describe('hasActiveFilters', () => {
  it('검색어가 있을 때만 true', () => {
    expect(hasActiveFilters(DEFAULT_ADMIN_MEMBER_QUERY)).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_MEMBER_QUERY, keyword: ' ' })).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_MEMBER_QUERY, keyword: '홍' })).toBe(true)
  })
})

describe('memberRowNumber', () => {
  it('첫 페이지 첫 행 = 전체 건수, 다음 페이지는 size만큼 감소, 마지막 행 = 1', () => {
    expect(memberRowNumber(45, 0, 20, 0)).toBe(45)
    expect(memberRowNumber(45, 1, 20, 0)).toBe(25)
    expect(memberRowNumber(45, 2, 20, 4)).toBe(1)
  })
})
