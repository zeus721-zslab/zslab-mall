import type {
  SellerProductQuestionListQuery,
  SellerProductQuestionListResponse,
} from '#layers/seller/app/types/seller-product-question'
import { toSellerProductQuestionApiParams } from '#layers/seller/app/lib/seller-product-question-query'
import type { AnswerDraftResponse } from '~/types/answer-draft'

/**
 * 셀러 상품 질문 API(Track 106-2). useSellerApi(셀러 쿠키 인증·CSRF 헤더·401/403 SELLER_SUSPENDED 분기) 경유이며 로딩·에러 상태는 호출부가 가진다.
 */
export function useSellerProductQuestions() {
  const api = useSellerApi()

  function list(query: SellerProductQuestionListQuery): Promise<SellerProductQuestionListResponse> {
    return api<SellerProductQuestionListResponse>('/v1/seller/product-questions', { query: toSellerProductQuestionApiParams(query) })
  }

  /** 답변 등록·수정(덮어쓰기 · 204). 다른 셀러 상품 404 · 숨김 질문 422 · 정지 셀러 403. */
  function answer(questionId: string, content: string): Promise<void> {
    // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해(TS2321) string으로 고정한다(useSellerClaims 선례).
    const path: string = `/v1/seller/product-questions/${questionId}/answer`
    return api<void>(path, { method: 'PUT', body: { content } })
  }

  /** 답안 초안(D-253 · 조회 시 계산 · 저장 없음). 다른 셀러 상품·삭제 404 · 정지 셀러도 조회는 허용. */
  function answerDraft(questionId: string): Promise<AnswerDraftResponse> {
    const path: string = `/v1/seller/product-questions/${questionId}/answer-draft`
    return api<AnswerDraftResponse>(path)
  }

  return { list, answer, answerDraft }
}
