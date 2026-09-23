import { IRREVERSIBLE, reversibleBy, riskConfirmMessage } from '~/lib/utils/risk-confirm'
import type { AdminMe, AdminOperatorListQuery, AdminOperatorSummary } from '#layers/admin/app/types/admin-operator'
import {
  ADMIN_OPERATOR_ROLE_LABEL,
  ADMIN_OPERATOR_ROLE_SEMANTIC,
  type AdminOperatorRole,
} from '#layers/admin/app/lib/constants/admin-operator'
import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'

/**
 * 운영자 목록 표시·활성 판정 순수 함수(FE-39). 화면 비활성은 미리 알려주는 안내일 뿐이며 실인가(SUPER_ADMIN 전용·자기 회수·마지막
 * SUPER_ADMIN)는 BE가 403/409로 강제한다. 화면은 BE보다 보수적이다: BE는 자기 ADMIN_OPERATOR 회수를 허용하지만 화면은 자기 행 전체를
 * 막고, 마지막 SUPER_ADMIN 판정은 목록이 완전히 보일 때만 한다(모르면 서버 409에 맡긴다).
 */

export interface OperatorRoleChip {
  text: string
  semantic: AdminSemantic
}

export function operatorRoleChip(role: AdminOperatorRole): OperatorRoleChip {
  return { text: ADMIN_OPERATOR_ROLE_LABEL[role], semantic: ADMIN_OPERATOR_ROLE_SEMANTIC[role] }
}

/** 행 표시명: 이름 → 이메일 → publicId(부트스트랩 SUPER_ADMIN은 이름이 없다). */
export function operatorDisplayName(row: Pick<AdminOperatorSummary, 'name' | 'email' | 'userPublicId'>): string {
  return row.name ?? row.email ?? row.userPublicId
}

export function isSelf(row: Pick<AdminOperatorSummary, 'userPublicId'>, me: AdminMe | null): boolean {
  return me !== null && me.userPublicId === row.userPublicId
}

/**
 * 현재 목록에서 SUPER_ADMIN 인원을 셀 수 있으면 그 수, 아니면 null. 목록이 "완전"할 때만 센다: 검색어 없음·역할 필터 전체 또는 SUPER_ADMIN·
 * 첫 페이지·다음 페이지 없음. 탈퇴 SUPER_ADMIN은 BE 인원수에 포함되지만 ACTIVE 목록에는 없어 화면이 더 보수적으로(비활성 쪽으로) 판정할 수 있다.
 */
export function superAdminCountInList(
  items: AdminOperatorSummary[],
  query: Pick<AdminOperatorListQuery, 'role' | 'keyword' | 'page'>,
  hasNext: boolean,
): number | null {
  const complete = query.keyword.trim() === '' && (query.role === null || query.role === 'SUPER_ADMIN') && query.page === 0 && !hasNext
  if (!complete) return null
  return items.filter((item) => item.roles.includes('SUPER_ADMIN')).length
}

/** 신규 운영자 등록 버튼 비활성 사유(null=활성). */
export function provisionBlockedReason(me: AdminMe | null): string | null {
  if (me === null) return '현재 관리자 정보를 불러오지 못해 등록할 수 없습니다.'
  if (!me.superAdmin) return '슈퍼 관리자만 운영자를 등록할 수 있습니다.'
  return null
}

/** 행 "역할 회수" 버튼 비활성 사유(null=활성). 자기 행은 역할과 무관하게 막는다(화면 정책·BE보다 보수적). */
export function revokeBlockedReason(row: AdminOperatorSummary, me: AdminMe | null): string | null {
  if (me === null) return '현재 관리자 정보를 불러오지 못해 회수할 수 없습니다.'
  if (!me.superAdmin) return '슈퍼 관리자만 역할을 회수할 수 있습니다.'
  if (isSelf(row, me)) return '자기 자신의 역할은 회수할 수 없습니다.'
  if (row.roles.length === 0) return '회수할 역할이 없습니다.'
  return null
}

/** 회수 다이얼로그의 역할 선택지 비활성 사유(null=선택 가능). 마지막 SUPER_ADMIN은 인원수를 알 때만 미리 막는다. */
export function roleRevokeBlockedReason(role: AdminOperatorRole, superAdminCount: number | null): string | null {
  if (role === 'SUPER_ADMIN' && superAdminCount !== null && superAdminCount <= 1) {
    return '마지막 슈퍼 관리자는 회수할 수 없습니다(시스템 잠금 방지).'
  }
  return null
}

/**
 * 회수 확인 문구. SUPER_ADMIN은 재부여 경로가 없음을 명시하고(D-186 §8 이월), ADMIN_OPERATOR는 남는 역할 유무로 접근 영향(로그인 불가 vs 유지)을
 * 나눠 안내한다.
 */
export function revokeConfirmMessage(row: AdminOperatorSummary, role: AdminOperatorRole): string {
  const who = row.email ? `${operatorDisplayName(row)} (${row.email})` : operatorDisplayName(row)
  const remaining = row.roles.filter((held) => held !== role)
  const remainingLine = remaining.length > 0
    ? `남은 역할(${remaining.map((held) => ADMIN_OPERATOR_ROLE_LABEL[held]).join(', ')})은 유지됩니다.`
    : null
  if (role === 'SUPER_ADMIN') {
    const lines = [`${who}의 슈퍼 관리자 역할을 회수합니다.`, '회수 즉시 운영자 등록·역할 회수 권한을 잃습니다.']
    if (remainingLine) lines.push(remainingLine)
    // 화면에는 슈퍼 관리자 부여 API가 없어(D-186 §8 이월) 되돌리려면 DB 작업이 필요하다 — 화면 기준으로는 불가역이다.
    return riskConfirmMessage(lines.join('\n'), IRREVERSIBLE)
  }
  const lines = [`${who}의 운영 관리자 역할을 회수합니다.`]
  lines.push(remaining.length > 0
    ? `남은 역할(${remaining.map((held) => ADMIN_OPERATOR_ROLE_LABEL[held]).join(', ')})은 유지되어 관리자 화면 접근은 계속 가능합니다.`
    : '회수 즉시 관리자 화면에 로그인할 수 없습니다.')
  return riskConfirmMessage(lines.join('\n'), reversibleBy('운영자 등록에서 다시 부여할 수 있습니다'))
}

/**
 * 권한 오류 문구: BE 도메인 403(SUPER_ADMIN 아님·자기 SUPER_ADMIN 회수)은 code가 같은 FORBIDDEN이라 서버 detail(구체 사유)을 우선 보여준다.
 * 그 외는 공용 코드 매핑(toAdminErrorMessage).
 */
export function toOperatorErrorMessage(error: unknown): string {
  if (extractErrorCode(error) === 'FORBIDDEN') {
    const detail = (error as { data?: { detail?: unknown } } | null)?.data?.detail
    if (typeof detail === 'string' && detail !== '') return detail
  }
  return toAdminErrorMessage(error)
}
