import type {
  AdminReconciliationIssue,
  AdminReconciliationIssuePage,
  AdminReconciliationListQuery,
  AdminReconciliationResolveBody,
} from '#layers/admin/app/types/admin-reconciliation'
import { toReconciliationApiParams } from '#layers/admin/app/lib/admin-reconciliation-view'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 불일치 API 호출 모음(Track 104-2 FE-66·BE D-216). useAdminApi(admin_token Bearer·401 처리) 경유이며 상태는 호출부가 소유한다.
 * 해결은 메모 필수(400)·이미 해결 422(RECONCILIATION_ISSUE_INVALID_STATE)·미존재 404를 throw한다.
 */
export function useAdminReconciliationIssues() {
  const api = useAdminApi()

  function list(query: AdminReconciliationListQuery): Promise<AdminReconciliationIssuePage> {
    return api<AdminReconciliationIssuePage>('/v1/admin/reconciliation-issues', { query: toReconciliationApiParams(query) })
  }

  function resolve(issueId: number, body: AdminReconciliationResolveBody): Promise<AdminReconciliationIssue> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminOrders 선례).
    const path: string = `/v1/admin/reconciliation-issues/${issueId}/resolve`
    return api<AdminReconciliationIssue>(path, { method: 'POST', body })
  }

  return { list, resolve }
}
