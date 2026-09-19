import type { AdminMemberDetail } from '#layers/admin/app/types/admin-member'
import type { ClaimType } from '~/lib/constants/claim'
import {
  ADMIN_BUYER_GRADE_LABEL,
  ADMIN_GRADE_SOURCE_LABEL,
  ADMIN_MEMBER_NAME_MAX,
  ADMIN_MEMBER_PHONE_MAX,
  ADMIN_MEMBER_PHONE_PATTERN,
  type AdminBuyerGradeCode,
  type AdminMemberActivityTab,
} from '#layers/admin/app/lib/constants/admin-member'
import { ADMIN_SELLER_MEMBER_ROLE_LABEL } from '#layers/admin/app/lib/constants/admin-seller'

/**
 * 관리자 회원 상세 표시·검증 순수 함수(Track 84 FE·admin-order-view 패턴). 컴포넌트는 표시·배선만, 규칙은 여기서 vitest로 고정한다.
 */

export interface MemberFormInput {
  name: string
  phone: string
}

/** 정보 수정 폼 검증(BE AdminMemberUpdateRequest와 동일 규칙: name 필수·≤50, phone 필수·≤20·휴대폰 형식). */
export function validateMemberForm(input: MemberFormInput): Record<string, string> {
  const errors: Record<string, string> = {}
  const name = input.name.trim()
  if (name === '') errors.name = '이름을 입력하세요.'
  else if (name.length > ADMIN_MEMBER_NAME_MAX) errors.name = `이름은 ${ADMIN_MEMBER_NAME_MAX}자 이하여야 합니다.`
  const phone = input.phone.trim()
  if (phone === '') errors.phone = '연락처를 입력하세요.'
  else if (phone.length > ADMIN_MEMBER_PHONE_MAX || !ADMIN_MEMBER_PHONE_PATTERN.test(phone)) {
    errors.phone = '휴대폰 번호 형식이 올바르지 않습니다(예: 010-1234-5678).'
  }
  return errors
}

export interface GradeFormInput {
  gradeCode: AdminBuyerGradeCode | null
  lockedUntil: string
}

/** 오늘(KST 로컬 날짜) yyyy-MM-dd. 유지 기한은 이 값보다 커야 한다(BE @Future). */
export function todayDateOnly(now: Date = new Date()): string {
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 날짜 입력 min(내일 yyyy-MM-dd) — 네이티브 date 입력의 오늘 이후만 선택 가능. */
export function minLockedUntil(now: Date = new Date()): string {
  const tomorrow = new Date(now)
  tomorrow.setDate(tomorrow.getDate() + 1)
  return todayDateOnly(tomorrow)
}

/** 등급 변경 폼 검증(BE AdminMemberGradeRequest와 동일 규칙: gradeCode 필수·lockedUntil 필수·오늘 이후). */
export function validateGradeForm(input: GradeFormInput, now: Date = new Date()): Record<string, string> {
  const errors: Record<string, string> = {}
  if (!input.gradeCode) errors.gradeCode = '등급을 선택하세요.'
  if (input.lockedUntil === '') errors.lockedUntil = '유지 기한을 선택하세요.'
  else if (!/^\d{4}-\d{2}-\d{2}$/.test(input.lockedUntil)) errors.lockedUntil = '날짜 형식이 올바르지 않습니다.'
  else if (input.lockedUntil <= todayDateOnly(now)) errors.lockedUntil = '유지 기한은 오늘 이후 날짜여야 합니다.'
  return errors
}

/** 등급 표시: "골드 · 수동(2026.10.01까지)" / 등급 없음은 "—". */
export function gradeLabel(grade: AdminMemberDetail['grade']): string {
  if (!grade || !grade.code) return '—'
  return ADMIN_BUYER_GRADE_LABEL[grade.code]
}

export function gradeSourceLabel(grade: AdminMemberDetail['grade']): string {
  if (!grade) return '—'
  return ADMIN_GRADE_SOURCE_LABEL[grade.source] ?? grade.source
}

/** 탈퇴 회원 여부 — 액션 4종 비활성 판정 단일 지점. */
export function isWithdrawn(detail: Pick<AdminMemberDetail, 'withdrawnAt'>): boolean {
  return Boolean(detail.withdrawnAt)
}

/** 임시 비밀번호 발급 가능 여부(활성 + 연락처 있음). */
export function canResetPassword(detail: Pick<AdminMemberDetail, 'withdrawnAt' | 'phone'>): boolean {
  return !isWithdrawn(detail) && typeof detail.phone === 'string' && detail.phone.trim() !== ''
}

/** 주문정보 탭 → 클레임 type. orders 탭은 null(주문 목록). */
export function tabClaimType(tab: AdminMemberActivityTab): ClaimType | null {
  switch (tab) {
    case 'cancel': return 'CANCEL'
    case 'return': return 'RETURN'
    case 'exchange': return 'EXCHANGE'
    default: return null
  }
}

/**
 * 탈퇴 다이얼로그 셀러 소속 경고(STEP 498·D-189 확정 4: 차단 없이 경고만). 소속이 없으면 null. lastActiveMember면 두 번째 문장을 더하고 화면이 강조한다.
 * 역할 라벨은 셀러 구성원 상수(대표/매니저/담당자)를 쓰고 roleCode가 없으면 "구성원"만.
 */
export function withdrawSellerWarning(detail: Pick<AdminMemberDetail, 'sellerMembership'>): { lines: string[]; emphasis: boolean } | null {
  const membership = detail.sellerMembership
  if (!membership) return null
  const role = membership.roleCode ? `구성원(${ADMIN_SELLER_MEMBER_ROLE_LABEL[membership.roleCode]})` : '구성원'
  const lines = [`이 회원은 ${membership.companyName} 셀러의 ${role}입니다. 탈퇴해도 셀러 소속은 유지되지만 로그인할 수 없게 됩니다.`]
  if (membership.lastActiveMember) lines.push('탈퇴하면 이 셀러에 로그인할 수 있는 구성원이 없어집니다.')
  return { lines, emphasis: membership.lastActiveMember }
}
