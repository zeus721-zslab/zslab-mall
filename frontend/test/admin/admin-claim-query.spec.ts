import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_CLAIM_QUERY,
  hasActiveClaimFilters,
  parseAdminClaimQuery,
  toAdminClaimApiParams,
  toAdminClaimRouteQuery,
} from '#layers/admin/app/lib/admin-claim-query'

// FE-28: URL query ↔ 화면 상태 ↔ BE GET /admin/claims 파라미터 순수 매핑(유형 탭 포함 8 파라미터·기간 변환은 FE-27 재사용).
describe('parseAdminClaimQuery', () => {
  it('빈 query → 기본 상태(전체 탭·LATEST·page 0·size 20·필터 없음)', () => {
    expect(parseAdminClaimQuery({})).toEqual(DEFAULT_ADMIN_CLAIM_QUERY)
  })

  it('전체 query 파싱·trim·타입 변환', () => {
    expect(parseAdminClaimQuery({
      type: 'CANCEL', status: 'REQUESTED', keyword: ' ORD-1 ', from: '2026-09-01', to: '2026-09-16', sort: 'OLDEST', page: '2', size: '50',
    })).toEqual({
      type: 'CANCEL', status: 'REQUESTED', keyword: 'ORD-1', from: '2026-09-01', to: '2026-09-16', sort: 'OLDEST', page: 2, size: 50,
    })
  })

  it('잘못된 값은 기본값으로 정규화(미지의 type·status·sort·날짜 형식·음수 page·허용 외 size)', () => {
    expect(parseAdminClaimQuery({
      type: 'REFUND', status: 'BOGUS', from: '20260901', to: '2026-9-1', sort: 'RANDOM', page: '-1', size: '33',
    })).toEqual(DEFAULT_ADMIN_CLAIM_QUERY)
  })

  it('검색어는 BE 한도 50자로 잘라 400을 예방·배열 query는 첫 값만', () => {
    expect(parseAdminClaimQuery({ keyword: 'a'.repeat(60) }).keyword).toHaveLength(50)
    expect(parseAdminClaimQuery({ type: ['RETURN', 'CANCEL'] }).type).toBe('RETURN')
  })
})

describe('toAdminClaimRouteQuery', () => {
  it('기본값 항목은 생략·유형 탭은 type으로', () => {
    expect(toAdminClaimRouteQuery(DEFAULT_ADMIN_CLAIM_QUERY)).toEqual({})
    expect(toAdminClaimRouteQuery({ ...DEFAULT_ADMIN_CLAIM_QUERY, type: 'CANCEL', status: 'REQUESTED', page: 1, size: 50, sort: 'OLDEST', keyword: ' x ' }))
      .toEqual({ type: 'CANCEL', status: 'REQUESTED', keyword: 'x', page: '1', size: '50', sort: 'OLDEST' })
  })

  it('parse ↔ toRouteQuery 왕복 보존', () => {
    const state = { ...DEFAULT_ADMIN_CLAIM_QUERY, type: 'RETURN' as const, from: '2026-09-01', to: '2026-09-16', page: 3 }
    expect(parseAdminClaimQuery(toAdminClaimRouteQuery(state) as Record<string, string>)).toEqual(state)
  })
})

describe('toAdminClaimApiParams', () => {
  it('sort·page·size는 항상, 나머지는 값 있을 때만·기간은 T00:00:00/T23:59:59 부착', () => {
    expect(toAdminClaimApiParams(DEFAULT_ADMIN_CLAIM_QUERY)).toEqual({ sort: 'LATEST', page: 0, size: 20 })
    expect(toAdminClaimApiParams({ ...DEFAULT_ADMIN_CLAIM_QUERY, type: 'CANCEL', status: 'REJECTED', keyword: 'ORD-1', from: '2026-09-01', to: '2026-09-16' }))
      .toEqual({ sort: 'LATEST', page: 0, size: 20, type: 'CANCEL', status: 'REJECTED', keyword: 'ORD-1', from: '2026-09-01T00:00:00', to: '2026-09-16T23:59:59' })
  })
})

describe('hasActiveClaimFilters', () => {
  it('유형 탭은 필터로 치지 않고 상태·기간·검색만 본다', () => {
    expect(hasActiveClaimFilters({ ...DEFAULT_ADMIN_CLAIM_QUERY, type: 'CANCEL' })).toBe(false)
    expect(hasActiveClaimFilters({ ...DEFAULT_ADMIN_CLAIM_QUERY, status: 'REQUESTED' })).toBe(true)
    expect(hasActiveClaimFilters({ ...DEFAULT_ADMIN_CLAIM_QUERY, keyword: 'x' })).toBe(true)
    expect(hasActiveClaimFilters({ ...DEFAULT_ADMIN_CLAIM_QUERY, to: '2026-09-16' })).toBe(true)
  })
})
