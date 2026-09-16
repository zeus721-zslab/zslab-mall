/**
 * 관리자 API 에러 코드(ProblemDetail.code) → 운영자 메시지(FE-25). 알 수 없는 코드는 detail 문자열 또는 일반 문구로 폴백한다.
 */
const ADMIN_ERROR_MESSAGES: Record<string, string> = {
  PRODUCT_NOT_FOUND: '상품을 찾을 수 없습니다(삭제되었거나 존재하지 않음).',
  PRODUCT_INVALID_STATE: '현재 상태에서 허용되지 않는 전환입니다.',
  PRODUCT_HAS_ORDER_HISTORY: '주문 이력이 있는 상품은 삭제할 수 없습니다. 판매중지로 전환하세요.',
  SELLER_NOT_FOUND: '셀러를 찾을 수 없습니다.',
  CATEGORY_NOT_FOUND: '카테고리를 찾을 수 없습니다.',
  // FE-27 관리자 주문·클레임·배송
  ORDER_NOT_FOUND: '주문 또는 주문 품목을 찾을 수 없습니다.',
  CLAIM_NOT_FOUND: '클레임을 찾을 수 없습니다.',
  DELIVERY_NOT_FOUND: '배송 정보를 찾을 수 없습니다.',
  CLAIM_STATE_INVALID: '현재 상태에서 처리할 수 없는 클레임입니다(취소 불가 품목·진행 중 클레임 중복·클레임 진행 중 품목의 송장 등록 등).',
  DELIVERY_INVALID_STATE: '현재 배송 상태에서 허용되지 않는 처리입니다(송장 등록은 결제완료 품목, 배송완료는 배송중만).',
  ORDER_ITEM_INVALID_STATE: '현재 품목 상태에서 허용되지 않는 처리입니다.',
  OPTIMISTIC_LOCK_FAILURE: '이미 종료됐거나 결제가 완료된 주문입니다. 최신 상태를 다시 확인하세요.',
  VALIDATION_FAILED: '입력값을 확인해 주세요.',
  MALFORMED_REQUEST: '잘못된 요청입니다.',
  FORBIDDEN: '권한이 없습니다.',
  UNAUTHENTICATED: '로그인이 필요합니다.',
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
}

const FALLBACK_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** ofetch 에러(FetchError)에서 ProblemDetail.code를 꺼낸다. 네트워크 오류·비JSON은 null. */
export function extractErrorCode(error: unknown): string | null {
  const code = (error as { data?: { code?: unknown } } | null)?.data?.code
  return typeof code === 'string' ? code : null
}

/** 코드 우선 → 알 수 없으면 서버 detail → 일반 문구. */
export function toAdminErrorMessage(error: unknown): string {
  const code = extractErrorCode(error)
  if (code && ADMIN_ERROR_MESSAGES[code]) return ADMIN_ERROR_MESSAGES[code]
  const detail = (error as { data?: { detail?: unknown } } | null)?.data?.detail
  return typeof detail === 'string' && detail !== '' ? detail : FALLBACK_MESSAGE
}

/** 일괄 결과 실패 항목의 code → 메시지(항목 message가 있으면 코드 문구 뒤에 병기하지 않고 코드 문구만). */
export function toBulkFailureMessage(code: string | undefined, message: string | undefined): string {
  if (code && ADMIN_ERROR_MESSAGES[code]) return ADMIN_ERROR_MESSAGES[code]
  return message && message !== '' ? message : FALLBACK_MESSAGE
}
