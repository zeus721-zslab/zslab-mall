import type { PagedResponse } from '~/types/order'
import type { MyProductQuestion } from '~/types/product-question'

/**
 * 구매자 상품 질문 쓰기·내 질문(Track 106-2 · BUYER 전용 /v1/product-questions/**). 실패(RFC7807)는 throw해 호출부가 product-question-error로
 * 문구를 정한다. 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해 string으로 고정한다(useReviewActions 선례).
 */
export function useProductQuestionActions() {
  const api = useBuyerApi()

  /** 등록(항상 공개). 201 {questionId}. */
  function create(productPublicId: string, content: string): Promise<{ questionId: string }> {
    return api<{ questionId: string }>('/v1/product-questions', { method: 'POST', body: { productId: productPublicId, content } })
  }

  /** 수정(미답변 + 공개일 때만 · 아니면 422). 204. */
  function update(questionId: string, content: string): Promise<void> {
    const path: string = `/v1/product-questions/${questionId}`
    return api<void>(path, { method: 'PUT', body: { content } })
  }

  /** 삭제(미답변일 때만 · 아니면 422). 204. */
  function remove(questionId: string): Promise<void> {
    const path: string = `/v1/product-questions/${questionId}`
    return api<void>(path, { method: 'DELETE' })
  }

  return { create, update, remove }
}

/**
 * 내 질문 목록(GET /v1/product-questions/me · 숨김 포함 · 최신순). page는 Ref로 받아 바뀌면 useFetch가 다시 부른다(useOrderList 선례).
 */
export function useMyProductQuestions(page: Ref<number>, size: number) {
  return useFetch<PagedResponse<MyProductQuestion>>('/v1/product-questions/me', {
    key: 'my-product-questions',
    $fetch: useBuyerApi(),
    query: { page, size },
  })
}
