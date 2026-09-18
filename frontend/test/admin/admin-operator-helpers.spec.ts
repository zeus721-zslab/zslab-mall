import { describe, it, expect } from 'vitest'
import {
  DEFAULT_ADMIN_OPERATOR_QUERY,
  hasActiveFilters,
  parseAdminOperatorQuery,
  toAdminOperatorApiParams,
  toAdminOperatorRouteQuery,
} from '#layers/admin/app/lib/admin-operator-query'
import {
  isSelf,
  operatorDisplayName,
  operatorRoleChip,
  provisionBlockedReason,
  revokeBlockedReason,
  revokeConfirmMessage,
  roleRevokeBlockedReason,
  superAdminCountInList,
  toOperatorErrorMessage,
} from '#layers/admin/app/lib/admin-operator-view'
import type { AdminMe, AdminOperatorSummary } from '#layers/admin/app/types/admin-operator'

// FE-39: 운영자 목록 URL 매핑·역할 배지·비활성 판정·겸직 표기·회수 확인 문구 순수 함수.
const SUPER_ME: AdminMe = { userPublicId: 'usr_ME', email: 'me@test.local', roles: ['SUPER_ADMIN'], superAdmin: true }
const OPERATOR_ME: AdminMe = { userPublicId: 'usr_OP', email: 'op@test.local', roles: ['ADMIN_OPERATOR'], superAdmin: false }

function row(overrides: Partial<AdminOperatorSummary> = {}): AdminOperatorSummary {
  return { userPublicId: 'usr_A', name: '홍운영', email: 'a@test.local', roles: ['ADMIN_OPERATOR'], hasBuyerRole: false, createdAt: '2026-09-18T10:00:00', ...overrides }
}

describe('admin-operator-query', () => {
  it('route.query 파싱: 미지의 role·status·size·음수 page는 기본값, 검색어는 50자 절단', () => {
    expect(parseAdminOperatorQuery({})).toEqual(DEFAULT_ADMIN_OPERATOR_QUERY)
    expect(parseAdminOperatorQuery({ role: 'BUYER', status: 'DELETED', page: '-1', size: '7' })).toEqual(DEFAULT_ADMIN_OPERATOR_QUERY)
    expect(parseAdminOperatorQuery({ role: 'SUPER_ADMIN', status: 'WITHDRAWN', keyword: ` ${'x'.repeat(60)} `, page: '2', size: '50' })).toEqual({
      role: 'SUPER_ADMIN', status: 'WITHDRAWN', keyword: 'x'.repeat(50), page: 2, size: 50,
    })
  })

  it('route query: 기본값 항목 생략', () => {
    expect(toAdminOperatorRouteQuery(DEFAULT_ADMIN_OPERATOR_QUERY)).toEqual({})
    expect(toAdminOperatorRouteQuery({ role: 'ADMIN_OPERATOR', status: 'WITHDRAWN', keyword: ' 홍 ', page: 1, size: 100 }))
      .toEqual({ role: 'ADMIN_OPERATOR', status: 'WITHDRAWN', keyword: '홍', page: '1', size: '100' })
  })

  it('API 파라미터: role 전체·빈 검색어 제외, status/page/size 항상', () => {
    expect(toAdminOperatorApiParams(DEFAULT_ADMIN_OPERATOR_QUERY)).toEqual({ status: 'ACTIVE', page: 0, size: 20 })
    expect(toAdminOperatorApiParams({ role: 'SUPER_ADMIN', status: 'ACTIVE', keyword: 'a', page: 0, size: 20 }))
      .toEqual({ role: 'SUPER_ADMIN', status: 'ACTIVE', keyword: 'a', page: 0, size: 20 })
  })

  it('hasActiveFilters: 역할·탈퇴·검색어 중 하나라도 있으면 true', () => {
    expect(hasActiveFilters(DEFAULT_ADMIN_OPERATOR_QUERY)).toBe(false)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_OPERATOR_QUERY, role: 'SUPER_ADMIN' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_OPERATOR_QUERY, status: 'WITHDRAWN' })).toBe(true)
    expect(hasActiveFilters({ ...DEFAULT_ADMIN_OPERATOR_QUERY, keyword: ' a ' })).toBe(true)
  })
})

describe('operatorRoleChip / operatorDisplayName / isSelf', () => {
  it('역할 배지: 슈퍼 관리자 warning·운영 관리자 info', () => {
    expect(operatorRoleChip('SUPER_ADMIN')).toEqual({ text: '슈퍼 관리자', semantic: 'warning' })
    expect(operatorRoleChip('ADMIN_OPERATOR')).toEqual({ text: '운영 관리자', semantic: 'info' })
  })

  it('표시명: 이름 → 이메일 → publicId(부트스트랩 SUPER_ADMIN은 이름 없음)', () => {
    expect(operatorDisplayName(row())).toBe('홍운영')
    expect(operatorDisplayName(row({ name: undefined }))).toBe('a@test.local')
    expect(operatorDisplayName(row({ name: undefined, email: undefined }))).toBe('usr_A')
  })

  it('isSelf: me 없으면 false·publicId 일치로 판정', () => {
    expect(isSelf(row(), null)).toBe(false)
    expect(isSelf(row({ userPublicId: 'usr_ME' }), SUPER_ME)).toBe(true)
    expect(isSelf(row(), SUPER_ME)).toBe(false)
  })
})

describe('provisionBlockedReason / revokeBlockedReason', () => {
  it('등록: me 없음·비SUPER_ADMIN 비활성, SUPER_ADMIN 활성', () => {
    expect(provisionBlockedReason(null)).toContain('불러오지 못해')
    expect(provisionBlockedReason(OPERATOR_ME)).toBe('슈퍼 관리자만 운영자를 등록할 수 있습니다.')
    expect(provisionBlockedReason(SUPER_ME)).toBeNull()
  })

  it('회수: me 없음·비SUPER_ADMIN·자기 행(역할 무관)·역할 없음 비활성, 그 외 활성', () => {
    expect(revokeBlockedReason(row(), null)).toContain('불러오지 못해')
    expect(revokeBlockedReason(row(), OPERATOR_ME)).toBe('슈퍼 관리자만 역할을 회수할 수 있습니다.')
    expect(revokeBlockedReason(row({ userPublicId: 'usr_ME', roles: ['SUPER_ADMIN', 'ADMIN_OPERATOR'] }), SUPER_ME)).toBe('자기 자신의 역할은 회수할 수 없습니다.')
    expect(revokeBlockedReason(row({ roles: [] }), SUPER_ME)).toBe('회수할 역할이 없습니다.')
    expect(revokeBlockedReason(row(), SUPER_ME)).toBeNull()
  })
})

describe('superAdminCountInList / roleRevokeBlockedReason', () => {
  const items = [row({ userPublicId: 'usr_1', roles: ['SUPER_ADMIN'] }), row({ userPublicId: 'usr_2', roles: ['SUPER_ADMIN', 'ADMIN_OPERATOR'] }), row({ userPublicId: 'usr_3' })]

  it('목록이 완전할 때만(검색어 없음·역할 전체/SUPER_ADMIN·첫 페이지·hasNext false) 인원을 세고, 아니면 null', () => {
    expect(superAdminCountInList(items, { role: null, keyword: '', page: 0 }, false)).toBe(2)
    expect(superAdminCountInList(items, { role: 'SUPER_ADMIN', keyword: '', page: 0 }, false)).toBe(2)
    expect(superAdminCountInList(items, { role: 'ADMIN_OPERATOR', keyword: '', page: 0 }, false)).toBeNull()
    expect(superAdminCountInList(items, { role: null, keyword: '홍', page: 0 }, false)).toBeNull()
    expect(superAdminCountInList(items, { role: null, keyword: '', page: 1 }, false)).toBeNull()
    expect(superAdminCountInList(items, { role: null, keyword: '', page: 0 }, true)).toBeNull()
  })

  it('마지막 SUPER_ADMIN(인원 ≤1)만 선택지 비활성, 모르면(null) 서버에 맡김', () => {
    expect(roleRevokeBlockedReason('SUPER_ADMIN', 1)).toContain('마지막 슈퍼 관리자')
    expect(roleRevokeBlockedReason('SUPER_ADMIN', 2)).toBeNull()
    expect(roleRevokeBlockedReason('SUPER_ADMIN', null)).toBeNull()
    expect(roleRevokeBlockedReason('ADMIN_OPERATOR', 1)).toBeNull()
  })
})

describe('revokeConfirmMessage / toOperatorErrorMessage', () => {
  it('SUPER_ADMIN: 재부여 불가 명시, 남은 역할 있으면 유지 안내', () => {
    const message = revokeConfirmMessage(row({ roles: ['SUPER_ADMIN', 'ADMIN_OPERATOR'] }), 'SUPER_ADMIN')
    expect(message).toContain('홍운영 (a@test.local)의 슈퍼 관리자 역할을 회수합니다.')
    expect(message).toContain('다시 부여할 수 없으므로')
    expect(message).toContain('남은 역할(운영 관리자)은 유지됩니다.')
    expect(revokeConfirmMessage(row({ roles: ['SUPER_ADMIN'] }), 'SUPER_ADMIN')).not.toContain('남은 역할')
  })

  it('ADMIN_OPERATOR: 남은 역할 없으면 로그인 불가·재부여 가능, 있으면 접근 유지', () => {
    expect(revokeConfirmMessage(row(), 'ADMIN_OPERATOR')).toContain('관리자 화면에 로그인할 수 없습니다')
    expect(revokeConfirmMessage(row({ roles: ['SUPER_ADMIN', 'ADMIN_OPERATOR'] }), 'ADMIN_OPERATOR')).toContain('남은 역할(슈퍼 관리자)은 유지되어')
  })

  it('FORBIDDEN은 서버 detail 우선, 그 외 코드는 공용 매핑', () => {
    expect(toOperatorErrorMessage({ data: { code: 'FORBIDDEN', detail: 'SUPER_ADMIN만 권한을 회수할 수 있습니다.' } })).toBe('SUPER_ADMIN만 권한을 회수할 수 있습니다.')
    expect(toOperatorErrorMessage({ data: { code: 'FORBIDDEN' } })).toBe('권한이 없습니다.')
    expect(toOperatorErrorMessage({ data: { code: 'LAST_SUPER_ADMIN', detail: 'x' } })).toContain('마지막 슈퍼 관리자')
    expect(toOperatorErrorMessage({ data: { code: 'ADMIN_OPERATOR_ALREADY_EXISTS' } })).toContain('이미 운영 관리자')
  })
})
