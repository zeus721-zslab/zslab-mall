import type { AdminSellerMember, AdminSellerMemberNewUser } from '#layers/admin/app/types/admin-seller'
import {
  ADMIN_MEMBER_PHONE_PATTERN,
} from '#layers/admin/app/lib/constants/admin-member'
import {
  ADMIN_SELLER_MEMBER_EMAIL_MAX,
  ADMIN_SELLER_MEMBER_EMAIL_PATTERN,
  ADMIN_SELLER_MEMBER_NAME_MAX,
  ADMIN_SELLER_MEMBER_PHONE_MAX,
  ADMIN_SELLER_MEMBER_ROLE_LABEL,
  ADMIN_SELLER_MEMBER_ROLE_TONE,
  SELLER_MEMBER_LAST_OWNER_TOOLTIP,
  type AdminSellerMemberRole,
} from '#layers/admin/app/lib/constants/admin-seller'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'

/** 셀러 구성원 화면 순수 판정·문구(FE-42·D-189). 컴포넌트가 아니라 여기 두어 vitest로 고정한다. */

/** 활성 구성원 = user가 해소됐고(soft-delete 아님) 탈퇴하지 않은 구성원(BE 가드의 "활성"과 같은 정의·loginableMemberCount와 동일 조건). */
export function isActiveMember(member: Pick<AdminSellerMember, 'userPublicId' | 'withdrawnAt'>): boolean {
  return member.userPublicId !== undefined && member.withdrawnAt === undefined
}

/** 활성 대표(OWNER) 수. 탈퇴·삭제 OWNER는 세지 않는다(BE SellerLastOwner 가드와 같은 집합). */
export function activeOwnerCount(members: AdminSellerMember[]): number {
  return members.filter((member) => member.roleCode === 'SELLER_OWNER' && isActiveMember(member)).length
}

/**
 * 마지막 활성 대표 제거·강등 불가 사유(툴팁). BE `AdminSellerMemberCommandService.assertNotLastActiveOwner`와 정확히 같은 판정:
 * 대상이 활성 OWNER이고 그 외 활성 OWNER가 0명일 때만 차단. 대상이 탈퇴자·OWNER 아님이면 null(허용 — 탈퇴 OWNER 행 제거는 셀러 2 정리 경로).
 * 판정 근거는 상세 응답 members뿐이며 상세가 오래됐으면 서버 409 SELLER_LAST_OWNER가 최종 방어선이다(미리보기 = 실제).
 */
export function lastActiveOwnerBlockedReason(members: AdminSellerMember[], target: AdminSellerMember): string | null {
  if (target.roleCode !== 'SELLER_OWNER' || !isActiveMember(target)) return null
  const otherActiveOwners = members.filter((member) => member !== target && member.userPublicId !== target.userPublicId
    && member.roleCode === 'SELLER_OWNER' && isActiveMember(member)).length
  return otherActiveOwners === 0 ? SELLER_MEMBER_LAST_OWNER_TOOLTIP : null
}

export interface MemberRoleChip {
  text: string
  chipClass: string
}

/** 역할 배지(admin-vuetify.css .adm-chip--*). roleCode가 없으면(역할 seed 불일치) '—'. */
export function memberRoleChip(role: AdminSellerMemberRole | undefined): MemberRoleChip {
  if (!role) return { text: '—', chipClass: 'adm-chip adm-chip--neutral' }
  return { text: ADMIN_SELLER_MEMBER_ROLE_LABEL[role], chipClass: `adm-chip adm-chip--${ADMIN_SELLER_MEMBER_ROLE_TONE[role]}` }
}

/** 역할 변경 다이얼로그 선택지: 현재 역할은 제외한다(같은 역할 재요청은 BE 422). */
export function selectableRoles(current: AdminSellerMemberRole | undefined): AdminSellerMemberRole[] {
  return (['SELLER_OWNER', 'SELLER_MANAGER', 'SELLER_STAFF'] as AdminSellerMemberRole[]).filter((role) => role !== current)
}

/**
 * 신규 계정 입력 검증. 세 필드 모두 필수이며 BE 제약을 로컬에서 선검증한다(빈 값·형식 위반 필드마다 오류 메시지·오류 없으면 빈 객체).
 * 대조(R2 외부 검토 지적 4·BE AdminSellerMemberNewUserRequest·User 컬럼 SoT): 이메일 = BE와 동일(@Pattern `^[^@\s]+@[^@\s]+\.[^@\s]+$`·max 254) /
 * 이름 = BE와 동일(max 50) / 휴대폰 = **BE보다 엄격**(BE는 @NotBlank·max 20만·FE는 국내 휴대폰 형식 `ADMIN_MEMBER_PHONE_PATTERN` 추가) —
 * 임시 비밀번호 SMS 수신처라 발송 불가 번호를 화면에서 먼저 거른다(BE 허용값 중 국내 휴대폰이 아닌 번호는 SMS를 받을 수 없어 실질 손실 없음).
 */
export function validateNewUserInput(input: AdminSellerMemberNewUser): Record<string, string> {
  const errors: Record<string, string> = {}
  const email = input.email.trim()
  const name = input.name.trim()
  const phone = input.phone.trim()
  if (email === '') errors.email = '이메일을 입력하세요.'
  else if (email.length > ADMIN_SELLER_MEMBER_EMAIL_MAX || !ADMIN_SELLER_MEMBER_EMAIL_PATTERN.test(email)) errors.email = '이메일 형식이 올바르지 않습니다.'
  if (name === '') errors.name = '이름을 입력하세요.'
  else if (name.length > ADMIN_SELLER_MEMBER_NAME_MAX) errors.name = `이름은 ${ADMIN_SELLER_MEMBER_NAME_MAX}자 이하여야 합니다.`
  if (phone === '') errors.phone = '휴대폰 번호를 입력하세요(임시 비밀번호 SMS 수신처).'
  else if (phone.length > ADMIN_SELLER_MEMBER_PHONE_MAX || !ADMIN_MEMBER_PHONE_PATTERN.test(phone)) errors.phone = '휴대폰 번호 형식이 올바르지 않습니다(예: 010-1234-5678).'
  return errors
}

/** 구성원 명령 오류 문구(코드별). 409가 원인별로 다른 코드로 오므로 각각 다른 문장을 준다(D-189 §1-A 7). 없으면 공용 표 → detail → 일반 문구. */
const SELLER_MEMBER_ERROR_MESSAGES: Record<string, string> = {
  MEMBER_ALREADY_WITHDRAWN: '탈퇴한 회원은 구성원으로 추가할 수 없습니다.',
  SELLER_USER_ALREADY_EXISTS: '이미 다른 셀러에 소속된 회원입니다(한 회원은 한 셀러에만 속할 수 있습니다). 이 셀러의 구성원이면 화면을 새로 고치세요.',
  SELLER_LAST_OWNER: '마지막 활성 대표(OWNER)는 제거·강등할 수 없습니다. 다른 구성원을 대표로 먼저 추가하거나 지정하세요.',
  SELLER_MEMBER_NOT_FOUND: '이 셀러의 구성원이 아닙니다. 화면을 새로 고칩니다.',
  SELLER_MEMBER_INVALID_STATE: '이미 같은 역할입니다.',
  EMAIL_ALREADY_EXISTS: '이미 사용 중인 이메일입니다(탈퇴 회원 포함). 기존 회원이면 "기존 회원 검색"으로 추가하세요.',
  TEMPORARY_PASSWORD_DELIVERY_FAILED: '임시 비밀번호 SMS 발송에 실패해 계정을 만들지 않았습니다. 잠시 후 다시 시도하거나 휴대폰 번호를 확인해 주세요.',
  USER_NOT_FOUND: '회원을 찾을 수 없습니다(삭제되었거나 존재하지 않음).',
}

export function toSellerMemberErrorMessage(error: unknown): string {
  const code = extractErrorCode(error)
  if (code && SELLER_MEMBER_ERROR_MESSAGES[code]) return SELLER_MEMBER_ERROR_MESSAGES[code]
  return toAdminErrorMessage(error)
}
