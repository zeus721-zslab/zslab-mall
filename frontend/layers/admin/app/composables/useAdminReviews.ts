import type {
  AdminReviewListQuery,
  AdminReviewListResponse,
  AdminReviewStatusChangeRequest,
} from '#layers/admin/app/types/admin-review'
import { toAdminReviewApiParams } from '#layers/admin/app/lib/admin-review-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 리뷰 관리 API(Track 106-1 · useAdminSellers 패턴). useAdminApi(관리자 쿠키·CSRF·401 처리) 경유이며 로딩·에러 상태는 호출부가 가진다.
 */
export function useAdminReviews() {
  const api = useAdminApi()

  function list(query: AdminReviewListQuery): Promise<AdminReviewListResponse> {
    return api<AdminReviewListResponse>('/v1/admin/reviews', { query: toAdminReviewApiParams(query) })
  }

  /** 숨김·해제(사유 필수). 204라 호출부가 목록을 다시 읽는다. 같은 상태 재요청은 422 REVIEW_INVALID_STATE. */
  function changeStatus(reviewId: string, body: AdminReviewStatusChangeRequest): Promise<void> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminSellers 선례).
    const path: string = `/v1/admin/reviews/${reviewId}/status`
    return api<void>(path, { method: 'PATCH', body })
  }

  return { list, changeStatus }
}
