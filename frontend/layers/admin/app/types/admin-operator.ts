import type { AdminOperatorRole, AdminOperatorStatus } from '#layers/admin/app/lib/constants/admin-operator'

/**
 * 관리자 운영자 API 타입(FE-39·D-186 BE 계약 1:1). nullable 필드는 BE NON_NULL 직렬화로 생략될 수 있어 optional
 * (부트스트랩 SUPER_ADMIN은 name 없음). 시각 문자열은 오프셋 없는 LocalDateTime이며 formatDateTime으로만 표시한다.
 */

/** 목록 행(BE AdminOperatorSummaryResponse). roles는 ADMIN 계열만, 일반회원 겸직은 hasBuyerRole. */
export interface AdminOperatorSummary {
  userPublicId: string
  name?: string
  email?: string
  roles: AdminOperatorRole[]
  hasBuyerRole: boolean
  createdAt: string
  withdrawnAt?: string
}

/** 페이징 봉투(BE PagedResponse·필드 5개). */
export interface AdminOperatorListResponse {
  items: AdminOperatorSummary[]
  page: number
  size: number
  totalCount: number
  hasNext: boolean
}

/** 현재 관리자(BE AdminMeResponse·GET /admin/me). roles는 보유 역할 전체(BUYER 포함 가능)라 string으로 둔다. */
export interface AdminMe {
  userPublicId: string
  name?: string
  email?: string
  roles: string[]
  superAdmin: boolean
}

/** 목록 화면 상태(URL query가 단일 소스). role null=전체. */
export interface AdminOperatorListQuery {
  role: AdminOperatorRole | null
  status: AdminOperatorStatus
  keyword: string
  page: number
  size: number
}

/** BE GET /admin/admin-operators 파라미터. */
export interface AdminOperatorApiParams {
  role?: AdminOperatorRole
  status: AdminOperatorStatus
  keyword?: string
  page: number
  size: number
}

/** POST /admin/admin-operators body(기존 회원에 ADMIN_OPERATOR 부여). */
export interface AdminOperatorProvisionRequest {
  userPublicId: string
}

/** DELETE /admin/users/{userPublicId}/roles/{roleCode} body(사유 필수). */
export interface AdminRoleRevocationRequest {
  reason: string
}
