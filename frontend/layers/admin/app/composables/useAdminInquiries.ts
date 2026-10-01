import type { AdminInquiryListQuery, AdminInquiryListResponse } from '#layers/admin/app/types/admin-inquiry'
import type { AnswerDraftResponse } from '~/types/answer-draft'
import { toAdminInquiryApiParams } from '#layers/admin/app/lib/admin-inquiry-query'
import { useAdminApi } from '#layers/admin/app/composables/useAdminApi'

/**
 * 관리자 운영자 문의 API(Track 106-4 · useAdminProductQuestions 선례). useAdminApi(관리자 쿠키·CSRF·401 처리) 경유이며 로딩·에러 상태는 호출부가
 * 가진다.
 */
export function useAdminInquiries() {
  const api = useAdminApi()

  function list(query: AdminInquiryListQuery): Promise<AdminInquiryListResponse> {
    return api<AdminInquiryListResponse>('/v1/admin/inquiries', { query: toAdminInquiryApiParams(query) })
  }

  /** 답변 등록·수정(덮어쓰기 · 같은 본문이면 BE가 무변경 처리). 204라 호출부가 목록을 다시 읽는다. 미존재·삭제 404 INQUIRY_NOT_FOUND. */
  function answer(inquiryId: string, content: string): Promise<void> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useAdminReviews 선례).
    const path: string = `/v1/admin/inquiries/${inquiryId}/answer`
    return api<void>(path, { method: 'PUT', body: { content } })
  }

  /** 답안 초안(D-253 · 조회 시 계산 · 저장 없음). 미존재·삭제 404 INQUIRY_NOT_FOUND. */
  function answerDraft(inquiryId: string): Promise<AnswerDraftResponse> {
    const path: string = `/v1/admin/inquiries/${inquiryId}/answer-draft`
    return api<AnswerDraftResponse>(path)
  }

  return { list, answer, answerDraft }
}
