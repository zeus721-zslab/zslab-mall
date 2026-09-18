import type {
  AdminMe,
  AdminOperatorListQuery,
  AdminOperatorListResponse,
  AdminOperatorProvisionRequest,
  AdminRoleRevocationRequest,
} from '#layers/admin/app/types/admin-operator'
import type { AdminOperatorRole } from '#layers/admin/app/lib/constants/admin-operator'
import { toAdminOperatorApiParams } from '#layers/admin/app/lib/admin-operator-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 운영자 관리 API 호출 모음(FE-39·D-186 BE·useAdminMembers 패턴). 전부 useAdminApi(admin_token Bearer·401 처리) 경유이며
 * 상태(로딩·에러)는 호출부가 소유한다. 부여는 기존 회원에 ADMIN_OPERATOR만, 회수는 DELETE + 사유 본문(CartController 선례).
 */
export function useAdminOperators() {
  const api = useAdminApi()

  function list(query: AdminOperatorListQuery): Promise<AdminOperatorListResponse> {
    return api<AdminOperatorListResponse>('/v1/admin/admin-operators', { query: toAdminOperatorApiParams(query) })
  }

  /** 현재 관리자(SUPER_ADMIN 여부·자기 publicId). 화면 비활성 판정 전용이며 실인가는 BE가 강제한다. */
  function me(): Promise<AdminMe> {
    return api<AdminMe>('/v1/admin/me')
  }

  function provision(body: AdminOperatorProvisionRequest): Promise<{ userPublicId: string }> {
    return api<{ userPublicId: string }>('/v1/admin/admin-operators', { method: 'POST', body })
  }

  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminMembers 선례).
  function revoke(userPublicId: string, role: AdminOperatorRole, body: AdminRoleRevocationRequest): Promise<void> {
    const path: string = `/v1/admin/users/${userPublicId}/roles/${role}`
    return api<void>(path, { method: 'DELETE', body })
  }

  return { list, me, provision, revoke }
}
