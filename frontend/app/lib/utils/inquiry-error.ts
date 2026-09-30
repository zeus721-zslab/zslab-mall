import { INQUIRY_CONTENT_MAX, INQUIRY_CONTENT_MIN } from '~/lib/constants/inquiry'

/**
 * 구매자 문의 쓰기 입력 검사·실패 문구(Track 106-4 · 작성 화면과 내 문의가 함께 쓴다 · product-question-error 선례). BE와 같은 기준(trim 후
 * 5~500자)으로 먼저 거르고, BE 거절은 코드로 문구를 고른다 — INQUIRY_INVALID_STATE·INQUIRY_NOT_FOUND면 호출부가 목록을 다시 읽는다.
 */

interface InquiryApiErrorLike {
  statusCode?: number
  data?: { code?: unknown }
}

/** 문의 본문 검사. 통과면 null, 아니면 안내 문구. */
export function validateInquiryContent(content: string): string | null {
  const length = content.trim().length
  if (length < INQUIRY_CONTENT_MIN) return `문의 내용은 ${INQUIRY_CONTENT_MIN}자 이상 입력해 주세요.`
  if (length > INQUIRY_CONTENT_MAX) return `문의 내용은 ${INQUIRY_CONTENT_MAX}자까지 입력할 수 있어요.`
  return null
}

/** ofetch 에러의 HTTP 상태(없으면 null · 네트워크 오류 등). */
export function inquiryErrorStatus(error: unknown): number | null {
  const statusCode = (error as InquiryApiErrorLike | null)?.statusCode
  return typeof statusCode === 'number' ? statusCode : null
}

/** ProblemDetail.code(없으면 빈 문자열). */
export function inquiryErrorCode(error: unknown): string {
  const code = (error as InquiryApiErrorLike | null)?.data?.code
  return typeof code === 'string' ? code : ''
}

/** 등록 실패 문구(401은 호출부가 로그인으로 보낸다). ORDER_NOT_FOUND = 첨부한 주문이 본인 주문이 아니거나 없다. */
export function inquiryCreateErrorMessage(error: unknown): string {
  if (inquiryErrorCode(error) === 'ORDER_NOT_FOUND') return '선택한 주문을 찾을 수 없어요. 주문을 다시 선택하거나 선택하지 않고 남겨 주세요.'
  if (inquiryErrorStatus(error) === 400) return `문의 내용은 ${INQUIRY_CONTENT_MIN}~${INQUIRY_CONTENT_MAX}자로 입력하고 카테고리를 골라 주세요.`
  return '문의를 남기지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

/** 수정·삭제 실패 문구(401은 호출부가 로그인으로 보낸다). */
export function inquiryChangeErrorMessage(error: unknown): string {
  const code = inquiryErrorCode(error)
  if (code === 'INQUIRY_INVALID_STATE') return '답변이 달린 문의는 수정·삭제할 수 없어요. 목록을 새로 불러왔어요.'
  if (code === 'INQUIRY_NOT_FOUND') return '이미 삭제된 문의입니다. 목록을 새로 불러왔어요.'
  if (inquiryErrorStatus(error) === 400) return `문의 내용은 ${INQUIRY_CONTENT_MIN}~${INQUIRY_CONTENT_MAX}자로 입력해 주세요.`
  return '처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

/** 실패 후 목록을 다시 읽어야 하는지(답변이 달림·이미 삭제). */
export function isStaleInquiryError(error: unknown): boolean {
  const code = inquiryErrorCode(error)
  return code === 'INQUIRY_INVALID_STATE' || code === 'INQUIRY_NOT_FOUND'
}
