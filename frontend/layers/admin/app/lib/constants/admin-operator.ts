import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'

/**
 * 관리자 운영자 관리 상수 단일 소스(FE-39·Track 89-E·CLAUDE.md 4층위 enum 잠금 (4)프론트). BE D-186 계약(AdminOperatorRoleFilter·
 * RoleCode ADMIN 계열·AdminMemberStatusFilter 재사용)과 1:1이며 운영자 목록·다이얼로그의 유일한 출처다.
 */

/** BE RoleCode 중 ADMIN 계열 2값(운영자 목록 roles·역할 필터·회수 대상). SUPER_ADMIN 부여 API는 없다(회수만 가능·D-186 §8 이월). */
export type AdminOperatorRole = 'SUPER_ADMIN' | 'ADMIN_OPERATOR'

export const ADMIN_OPERATOR_ROLE_LABEL: Record<AdminOperatorRole, string> = {
  SUPER_ADMIN: '슈퍼 관리자',
  ADMIN_OPERATOR: '운영 관리자',
}

/** 역할 배지 색: 슈퍼 관리자는 경고(권한 최상위·회수 주의), 운영 관리자는 중립. */
export const ADMIN_OPERATOR_ROLE_SEMANTIC: Record<AdminOperatorRole, AdminSemantic> = {
  SUPER_ADMIN: 'warning',
  ADMIN_OPERATOR: 'info',
}

export const ADMIN_OPERATOR_ROLE_OPTIONS: { value: AdminOperatorRole | null; title: string }[] = [
  { value: null, title: '전체 역할' },
  { value: 'SUPER_ADMIN', title: ADMIN_OPERATOR_ROLE_LABEL.SUPER_ADMIN },
  { value: 'ADMIN_OPERATOR', title: ADMIN_OPERATOR_ROLE_LABEL.ADMIN_OPERATOR },
]

/** BE AdminMemberStatusFilter 재사용(withdrawn_at NULL 여부). 회원 목록과 달리 운영자는 한 화면에서 select로 고른다. */
export type AdminOperatorStatus = 'ACTIVE' | 'WITHDRAWN'

export const ADMIN_OPERATOR_STATUS_OPTIONS: { value: AdminOperatorStatus; title: string }[] = [
  { value: 'ACTIVE', title: '활성' },
  { value: 'WITHDRAWN', title: '탈퇴' },
]
export const DEFAULT_ADMIN_OPERATOR_STATUS: AdminOperatorStatus = 'ACTIVE'

/** BE 검색어 상한(AdminOperatorQueryService MAX_KEYWORD_LENGTH=50·이름·이메일). */
export const ADMIN_OPERATOR_KEYWORD_MAX = 50
export const ADMIN_OPERATOR_PAGE_SIZES: number[] = [20, 50, 100]
export const DEFAULT_ADMIN_OPERATOR_PAGE_SIZE = 20

/** 회수 사유 상한(BE AdminRoleRevocationRequest @Size 200). 부여는 사유 없음(D-186). */
export const ADMIN_OPERATOR_REVOKE_REASON_MAX = 200

/** 신규 운영자 등록 다이얼로그의 회원 검색 결과 상한(회원 목록 API size). */
export const ADMIN_OPERATOR_MEMBER_SEARCH_SIZE = 10
