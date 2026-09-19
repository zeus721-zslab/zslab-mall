import { describe, it, expect } from 'vitest'
import {
  activeOwnerCount,
  isActiveMember,
  lastActiveOwnerBlockedReason,
  memberRoleChip,
  selectableRoles,
  toSellerMemberErrorMessage,
  validateNewUserInput,
} from '#layers/admin/app/lib/admin-seller-member-view'
import { SELLER_MEMBER_LAST_OWNER_TOOLTIP } from '#layers/admin/app/lib/constants/admin-seller'
import type { AdminSellerMember } from '#layers/admin/app/types/admin-seller'

// FE-42: 마지막 활성 대표 판정(BE assertNotLastActiveOwner와 동일·탈퇴자 제외)·역할 배지·신규 계정 입력 검증·409 코드별 문구.
function member(overrides: Partial<AdminSellerMember>): AdminSellerMember {
  return { userPublicId: 'usr_x', roleCode: 'SELLER_STAFF', joinedAt: '2026-09-19T10:00:00', ...overrides }
}
const activeOwner = member({ userPublicId: 'usr_owner', name: '대표', roleCode: 'SELLER_OWNER' })
const withdrawnOwner = member({ userPublicId: 'usr_wdr', email: 'w@t.local', roleCode: 'SELLER_OWNER', withdrawnAt: '2026-09-18T00:26:27' })
const deletedOwner = member({ userPublicId: undefined, roleCode: 'SELLER_OWNER' })
const manager = member({ userPublicId: 'usr_mgr', name: '매니저', roleCode: 'SELLER_MANAGER' })
const secondOwner = member({ userPublicId: 'usr_owner2', name: '공동대표', roleCode: 'SELLER_OWNER' })

function apiError(code: string, extra: Record<string, unknown> = {}): unknown {
  return { data: { code, detail: 'server detail', ...extra } }
}

describe('admin-seller-member-view', () => {
  it('활성 판정: user 해소 ∧ 미탈퇴만 활성·활성 대표 수는 탈퇴·삭제 OWNER를 세지 않는다', () => {
    expect(isActiveMember(activeOwner)).toBe(true)
    expect(isActiveMember(withdrawnOwner)).toBe(false)
    expect(isActiveMember(deletedOwner)).toBe(false)
    expect(activeOwnerCount([activeOwner, withdrawnOwner, deletedOwner, manager])).toBe(1)
    expect(activeOwnerCount([withdrawnOwner])).toBe(0)
    expect(activeOwnerCount([activeOwner, secondOwner])).toBe(2)
  })

  it('마지막 활성 대표: 활성 OWNER 1명 + 탈퇴 OWNER → 활성 OWNER 제거·강등 차단, 탈퇴 OWNER 행·매니저는 허용(셀러 2 정리 경로)', () => {
    const members = [activeOwner, withdrawnOwner, manager]
    expect(lastActiveOwnerBlockedReason(members, activeOwner)).toBe(SELLER_MEMBER_LAST_OWNER_TOOLTIP)
    expect(lastActiveOwnerBlockedReason(members, withdrawnOwner)).toBeNull()
    expect(lastActiveOwnerBlockedReason(members, manager)).toBeNull()
    // 유일 구성원이 탈퇴 OWNER(셀러 2 형태) → 제거 허용
    expect(lastActiveOwnerBlockedReason([withdrawnOwner], withdrawnOwner)).toBeNull()
    // 활성 OWNER 2명이면 어느 쪽도 허용
    expect(lastActiveOwnerBlockedReason([activeOwner, secondOwner], activeOwner)).toBeNull()
    expect(lastActiveOwnerBlockedReason([activeOwner, secondOwner], secondOwner)).toBeNull()
    // 활성 OWNER + 탈퇴 OWNER 2명 → 탈퇴자는 세지 않으므로 여전히 차단
    expect(lastActiveOwnerBlockedReason([activeOwner, withdrawnOwner, member({ userPublicId: 'usr_wdr2', roleCode: 'SELLER_OWNER', withdrawnAt: '2026-09-01T00:00:00' })], activeOwner))
      .toBe(SELLER_MEMBER_LAST_OWNER_TOOLTIP)
  })

  it('역할 배지·선택지: 대표 info / 매니저·담당자 neutral / 미상 "—" · 변경 선택지는 현재 역할 제외', () => {
    expect(memberRoleChip('SELLER_OWNER')).toEqual({ text: '대표', chipClass: 'adm-chip adm-chip--info' })
    expect(memberRoleChip('SELLER_MANAGER')).toEqual({ text: '매니저', chipClass: 'adm-chip adm-chip--neutral' })
    expect(memberRoleChip('SELLER_STAFF').text).toBe('담당자')
    expect(memberRoleChip(undefined).text).toBe('—')
    expect(selectableRoles('SELLER_OWNER')).toEqual(['SELLER_MANAGER', 'SELLER_STAFF'])
    expect(selectableRoles('SELLER_STAFF')).toEqual(['SELLER_OWNER', 'SELLER_MANAGER'])
    expect(selectableRoles(undefined)).toHaveLength(3)
  })

  it('신규 계정 입력 검증: 필수·이메일 형식·휴대폰 형식·길이 → 필드별 오류, 정상이면 빈 객체', () => {
    expect(validateNewUserInput({ email: '', name: '', phone: '' })).toEqual({
      email: '이메일을 입력하세요.', name: '이름을 입력하세요.', phone: '휴대폰 번호를 입력하세요(임시 비밀번호 SMS 수신처).',
    })
    expect(validateNewUserInput({ email: 'not-an-email', name: '홍길동', phone: '02-123-4567' })).toEqual({
      email: '이메일 형식이 올바르지 않습니다.', phone: '휴대폰 번호 형식이 올바르지 않습니다(예: 010-1234-5678).',
    })
    expect(validateNewUserInput({ email: 'a@b.co', name: 'x'.repeat(51), phone: '01012345678' })).toEqual({ name: '이름은 50자 이하여야 합니다.' })
    expect(validateNewUserInput({ email: 'new@seller.test', name: '홍길동', phone: '010-1234-5678' })).toEqual({})
  })

  it('오류 문구: 409 원인별(탈퇴·타 셀러 소속·마지막 대표·이메일 중복)과 422·404·502가 서로 다른 문장, 미상 코드는 공용 표 → detail', () => {
    const messages = ['MEMBER_ALREADY_WITHDRAWN', 'SELLER_USER_ALREADY_EXISTS', 'SELLER_LAST_OWNER', 'EMAIL_ALREADY_EXISTS',
      'SELLER_MEMBER_INVALID_STATE', 'SELLER_MEMBER_NOT_FOUND', 'TEMPORARY_PASSWORD_DELIVERY_FAILED']
      .map((code) => toSellerMemberErrorMessage(apiError(code)))
    expect(new Set(messages).size).toBe(messages.length)
    expect(toSellerMemberErrorMessage(apiError('MEMBER_ALREADY_WITHDRAWN'))).toContain('탈퇴한 회원')
    expect(toSellerMemberErrorMessage(apiError('SELLER_USER_ALREADY_EXISTS'))).toContain('다른 셀러')
    expect(toSellerMemberErrorMessage(apiError('SELLER_LAST_OWNER'))).toContain('마지막 활성 대표')
    expect(toSellerMemberErrorMessage(apiError('EMAIL_ALREADY_EXISTS'))).toContain('이미 사용 중인 이메일')
    expect(toSellerMemberErrorMessage(apiError('TEMPORARY_PASSWORD_DELIVERY_FAILED'))).toContain('계정을 만들지 않았습니다')
    expect(toSellerMemberErrorMessage(apiError('SELLER_NOT_FOUND'))).toBe('셀러를 찾을 수 없습니다.')
    expect(toSellerMemberErrorMessage(apiError('SOMETHING_ELSE'))).toBe('server detail')
  })
})
