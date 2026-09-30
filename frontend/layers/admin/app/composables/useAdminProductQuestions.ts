import type {
  AdminProductQuestionListQuery,
  AdminProductQuestionListResponse,
  AdminProductQuestionStatusChangeRequest,
} from '#layers/admin/app/types/admin-product-question'
import { toAdminProductQuestionApiParams } from '#layers/admin/app/lib/admin-product-question-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 상품 질문 API(Track 106-2 · useAdminReviews 복제). useAdminApi(관리자 쿠키·CSRF·401 처리) 경유이며 로딩·에러 상태는 호출부가 가진다.
 * 관리자는 숨김·해제만 한다(답변 API 없음).
 */
export function useAdminProductQuestions() {
  const api = useAdminApi()

  function list(query: AdminProductQuestionListQuery): Promise<AdminProductQuestionListResponse> {
    return api<AdminProductQuestionListResponse>('/v1/admin/product-questions', { query: toAdminProductQuestionApiParams(query) })
  }

  /** 숨김·해제(사유 필수). 204라 호출부가 목록을 다시 읽는다. 같은 상태 재요청은 422 PRODUCT_QUESTION_INVALID_STATE. */
  function changeStatus(questionId: string, body: AdminProductQuestionStatusChangeRequest): Promise<void> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminReviews 선례).
    const path: string = `/v1/admin/product-questions/${questionId}/status`
    return api<void>(path, { method: 'PATCH', body })
  }

  return { list, changeStatus }
}
