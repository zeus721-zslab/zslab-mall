import { PRODUCT_QUESTION_CONTENT_MAX, PRODUCT_QUESTION_CONTENT_MIN } from '~/lib/constants/product-question'

/**
 * 구매자 질문 쓰기 입력 검사·실패 문구(Track 106-2 · 상품 상세 섹션과 내 질문이 함께 쓴다). BE와 같은 기준(trim 후 5~500자)으로 먼저 거르고,
 * BE 거절은 상태 코드로 문구를 고른다 — 422는 규칙 위반(답변이 달렸거나 숨김)이라 호출부가 목록을 다시 읽는다.
 */

/** 질문 본문 검사. 통과면 null, 아니면 안내 문구. */
export function validateQuestionContent(content: string): string | null {
  const length = content.trim().length
  if (length < PRODUCT_QUESTION_CONTENT_MIN) return `질문은 ${PRODUCT_QUESTION_CONTENT_MIN}자 이상 입력해 주세요.`
  if (length > PRODUCT_QUESTION_CONTENT_MAX) return `질문은 ${PRODUCT_QUESTION_CONTENT_MAX}자까지 입력할 수 있어요.`
  return null
}

/** ofetch 에러의 HTTP 상태(없으면 null · 네트워크 오류 등). */
export function questionErrorStatus(error: unknown): number | null {
  const statusCode = (error as { statusCode?: number } | null)?.statusCode
  return typeof statusCode === 'number' ? statusCode : null
}

/** 등록 실패 문구(401은 호출부가 로그인으로 보낸다). */
export function questionCreateErrorMessage(error: unknown): string {
  const status = questionErrorStatus(error)
  if (status === 400) return `질문은 ${PRODUCT_QUESTION_CONTENT_MIN}~${PRODUCT_QUESTION_CONTENT_MAX}자로 입력해 주세요.`
  if (status === 404) return '지금은 질문을 남길 수 없는 상품입니다.'
  return '질문을 남기지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

/** 수정·삭제 실패 문구(401은 호출부가 로그인으로 보낸다). 422·404면 목록이 오래됐으므로 호출부가 다시 읽는다. */
export function questionChangeErrorMessage(error: unknown): string {
  const status = questionErrorStatus(error)
  if (status === 422) return '답변이 달렸거나 숨김 처리된 질문은 수정·삭제할 수 없어요. 목록을 새로 불러왔어요.'
  if (status === 404) return '이미 삭제된 질문입니다. 목록을 새로 불러왔어요.'
  if (status === 400) return `질문은 ${PRODUCT_QUESTION_CONTENT_MIN}~${PRODUCT_QUESTION_CONTENT_MAX}자로 입력해 주세요.`
  return '처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

/** 실패 후 목록을 다시 읽어야 하는지(규칙 거절·이미 삭제). */
export function isStaleQuestionError(error: unknown): boolean {
  const status = questionErrorStatus(error)
  return status === 422 || status === 404
}
