import { describe, it, expect } from 'vitest'
import {
  canResetPassword,
  gradeLabel,
  gradeSourceLabel,
  isWithdrawn,
  minLockedUntil,
  tabClaimType,
  todayDateOnly,
  validateGradeForm,
  validateMemberForm,
  withdrawSellerWarning,
} from '#layers/admin/app/lib/admin-member-view'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import {
  ADMIN_MEMBERS_PATH,
  ADMIN_MEMBERS_WITHDRAWN_PATH,
  ADMIN_ORDERS_PATH,
  resolveBackPath,
} from '#layers/admin/app/lib/admin-back-path'

// Track 84 FE: 회원 상세 순수 함수(폼 검증·라벨·탭 매핑)·에러 문구 4코드·회원 경로 back 허용.
describe('validateMemberForm', () => {
  it('정상(하이픈 유무 모두) → 오류 없음', () => {
    expect(validateMemberForm({ name: '홍길동', phone: '010-1234-5678' })).toEqual({})
    expect(validateMemberForm({ name: '홍길동', phone: '01012345678' })).toEqual({})
  })

  it('이름 빈값·50자 초과 / 연락처 빈값·형식 오류(지역번호·짧은 번호) → 필드별 오류', () => {
    expect(validateMemberForm({ name: ' ', phone: '010-1234-5678' })).toHaveProperty('name')
    expect(validateMemberForm({ name: 'a'.repeat(51), phone: '010-1234-5678' })).toHaveProperty('name')
    expect(validateMemberForm({ name: '홍', phone: '' })).toHaveProperty('phone')
    expect(validateMemberForm({ name: '홍', phone: '02-123-4567' })).toHaveProperty('phone')
    expect(validateMemberForm({ name: '홍', phone: '010-12-3456' })).toHaveProperty('phone')
  })
})

describe('validateGradeForm', () => {
  const now = new Date(2026, 8, 17, 15, 0, 0) // 2026-09-17 KST 로컬

  it('등급 + 내일 이후 날짜 → 오류 없음·오늘·과거·빈값·형식 오류·등급 미선택 → 오류', () => {
    expect(validateGradeForm({ gradeCode: 'GOLD', lockedUntil: '2026-09-18' }, now)).toEqual({})
    expect(validateGradeForm({ gradeCode: 'GOLD', lockedUntil: '2026-09-17' }, now)).toHaveProperty('lockedUntil')
    expect(validateGradeForm({ gradeCode: 'GOLD', lockedUntil: '2026-01-01' }, now)).toHaveProperty('lockedUntil')
    expect(validateGradeForm({ gradeCode: 'GOLD', lockedUntil: '' }, now)).toHaveProperty('lockedUntil')
    expect(validateGradeForm({ gradeCode: 'GOLD', lockedUntil: '20261001' }, now)).toHaveProperty('lockedUntil')
    expect(validateGradeForm({ gradeCode: null, lockedUntil: '2026-10-01' }, now)).toHaveProperty('gradeCode')
  })

  it('todayDateOnly·minLockedUntil은 로컬 날짜 yyyy-MM-dd(월말 롤오버 포함)', () => {
    expect(todayDateOnly(now)).toBe('2026-09-17')
    expect(minLockedUntil(now)).toBe('2026-09-18')
    expect(minLockedUntil(new Date(2026, 8, 30))).toBe('2026-10-01')
  })
})

describe('라벨·판정·탭 매핑', () => {
  it('gradeLabel·gradeSourceLabel: 등급 없음 —·코드/출처 한글', () => {
    expect(gradeLabel(undefined)).toBe('—')
    expect(gradeLabel({ code: 'PLATINUM', source: 'MANUAL' })).toBe('플래티넘')
    expect(gradeSourceLabel({ code: 'SILVER', source: 'AUTO' })).toBe('자동')
    expect(gradeSourceLabel({ code: 'SILVER', source: 'MANUAL' })).toBe('수동')
  })

  it('isWithdrawn·canResetPassword: 탈퇴면 전부 불가·연락처 없으면 발급 불가', () => {
    expect(isWithdrawn({ withdrawnAt: '2026-09-01T00:00:00' })).toBe(true)
    expect(isWithdrawn({})).toBe(false)
    expect(canResetPassword({ phone: '010-1234-5678' })).toBe(true)
    expect(canResetPassword({ phone: ' ' })).toBe(false)
    expect(canResetPassword({})).toBe(false)
    expect(canResetPassword({ phone: '010-1234-5678', withdrawnAt: '2026-09-01T00:00:00' })).toBe(false)
  })

  it('withdrawSellerWarning(STEP 498): 미소속 null / 소속이면 상호·역할 1줄 / lastActiveMember면 2줄 + 강조 / roleCode 없으면 "구성원"', () => {
    expect(withdrawSellerWarning({})).toBeNull()
    const one = withdrawSellerWarning({ sellerMembership: { sellerPublicId: 'slr_1', companyName: '데모 리빙샵', roleCode: 'SELLER_OWNER', lastActiveMember: false } })
    expect(one).toEqual({ emphasis: false, lines: ['이 회원은 데모 리빙샵 셀러의 구성원(대표)입니다. 탈퇴해도 셀러 소속은 유지되지만 로그인할 수 없게 됩니다.'] })
    const last = withdrawSellerWarning({ sellerMembership: { sellerPublicId: 'slr_1', companyName: '데모 리빙샵', roleCode: 'SELLER_STAFF', lastActiveMember: true } })
    expect(last?.emphasis).toBe(true)
    expect(last?.lines).toEqual([
      '이 회원은 데모 리빙샵 셀러의 구성원(담당자)입니다. 탈퇴해도 셀러 소속은 유지되지만 로그인할 수 없게 됩니다.',
      '탈퇴하면 이 셀러에 로그인할 수 있는 구성원이 없어집니다.',
    ])
    expect(withdrawSellerWarning({ sellerMembership: { sellerPublicId: 'slr_1', companyName: 'X', lastActiveMember: false } })?.lines[0]).toContain('셀러의 구성원입니다')
  })

  it('tabClaimType: orders → null·취소/반품/교환 → ClaimType', () => {
    expect(tabClaimType('orders')).toBeNull()
    expect(tabClaimType('cancel')).toBe('CANCEL')
    expect(tabClaimType('return')).toBe('RETURN')
    expect(tabClaimType('exchange')).toBe('EXCHANGE')
  })
})

describe('toAdminErrorMessage 회원 코드 4 + USER_NOT_FOUND', () => {
  it('코드별 운영자 문구', () => {
    expect(toAdminErrorMessage({ data: { code: 'MEMBER_ACTIVITY_IN_PROGRESS' } })).toBe('진행 중인 주문 또는 클레임이 있어 탈퇴할 수 없습니다.')
    expect(toAdminErrorMessage({ data: { code: 'MEMBER_ALREADY_WITHDRAWN' } })).toBe('이미 탈퇴한 회원입니다.')
    expect(toAdminErrorMessage({ data: { code: 'MEMBER_PHONE_MISSING' } })).toBe('연락처가 없어 임시 비밀번호를 발송할 수 없습니다.')
    expect(toAdminErrorMessage({ data: { code: 'TEMPORARY_PASSWORD_DELIVERY_FAILED' } })).toBe('SMS 발송에 실패했습니다. 비밀번호는 변경되지 않았습니다.')
    expect(toAdminErrorMessage({ data: { code: 'USER_NOT_FOUND' } })).toBe('회원을 찾을 수 없습니다.')
  })

  it('D-230: LAST_SUPER_ADMIN(탈퇴·권한 해제 공통)·DEMO_ACCOUNT_PROTECTED(데모 계정 보호) → BE와 같은 문구', () => {
    expect(toAdminErrorMessage({ data: { code: 'LAST_SUPER_ADMIN', detail: 'x' } })).toBe('마지막 슈퍼 관리자는 탈퇴하거나 권한을 해제할 수 없습니다.')
    expect(toAdminErrorMessage({ data: { code: 'DEMO_ACCOUNT_PROTECTED', detail: 'x' } })).toBe('데모 계정은 이 기능을 사용할 수 없습니다.')
  })
})

describe('resolveBackPath 회원 경로(Track 84)', () => {
  it('회원 base: 일반회원·탈퇴회원 목록(쿼리 포함) 허용·그 외는 일반회원 목록', () => {
    expect(resolveBackPath('/admin/members?keyword=홍&page=1', ADMIN_MEMBERS_PATH)).toBe('/admin/members?keyword=홍&page=1')
    expect(resolveBackPath(ADMIN_MEMBERS_WITHDRAWN_PATH, ADMIN_MEMBERS_PATH)).toBe('/admin/members/withdrawn')
    expect(resolveBackPath('/admin/members/withdrawn?page=2', ADMIN_MEMBERS_PATH)).toBe('/admin/members/withdrawn?page=2')
    expect(resolveBackPath('/admin/orders', ADMIN_MEMBERS_PATH)).toBe('/admin/members')
    expect(resolveBackPath('https://evil.example/', ADMIN_MEMBERS_PATH)).toBe('/admin/members')
  })

  it('주문 base: 회원 상세(/admin/members/usr_…?tab=…)에서 진입한 back을 허용·기존 주문/클레임 목록 동작 불변', () => {
    expect(resolveBackPath('/admin/members/usr_01ABC?tab=cancel&page=1', ADMIN_ORDERS_PATH)).toBe('/admin/members/usr_01ABC?tab=cancel&page=1')
    expect(resolveBackPath('/admin/orders/claims?type=RETURN', ADMIN_ORDERS_PATH)).toBe('/admin/orders/claims?type=RETURN')
    expect(resolveBackPath('/admin/products?page=2', ADMIN_ORDERS_PATH)).toBe('/admin/orders')
  })
})
