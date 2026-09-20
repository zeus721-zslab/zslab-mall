import { SELLER_SUSPENDED_ERROR_CODE } from '#layers/seller/app/lib/constants/auth'

/**
 * 셀러 API 에러 → 셀러 문구(Track 90-B-3·관리자 admin-error-message 복제·FE-44 §8 이월 해소). 코드(ProblemDetail.code) 우선 → 코드가 없거나 미지면
 * HTTP 상태별 폴백(401·403·404·409·422) → 서버 detail → 일반 문구. 403 SELLER_SUSPENDED는 useSellerApi가 배너 플래그를 켜지만
 * **각 쓰기 호출부가 이 문구를 토스트로도 보여야 한다**(배너에만 의존 금지·호출부가 삼키면 배너만 남는다).
 */
const SELLER_ERROR_MESSAGES: Record<string, string> = {
  [SELLER_SUSPENDED_ERROR_CODE]: '정지 상태의 셀러는 변경 작업을 할 수 없습니다. 조회만 가능하며 문의는 관리자에게 하세요.',
  UNAUTHENTICATED: '로그인이 필요합니다.',
  FORBIDDEN: '권한이 없습니다.',
  VALIDATION_FAILED: '입력값을 확인해 주세요.',
  MALFORMED_REQUEST: '잘못된 요청입니다.',
  INTERNAL_ERROR: '서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  // 주문·출고(D-191·prepare-shipment)
  ORDER_NOT_FOUND: '주문 품목을 찾을 수 없습니다(내 품목이 아니거나 미결제 주문).',
  ORDER_ITEM_INVALID_STATE: '현재 품목 상태에서 허용되지 않는 처리입니다(출고는 결제완료 품목만).',
  CLAIM_STATE_INVALID: '클레임이 진행 중인 품목은 출고할 수 없습니다.',
  // 배송(D-191·mark-delivered·송장 정정)
  DELIVERY_NOT_FOUND: '배송을 찾을 수 없습니다(내 배송이 아니거나 삭제됨).',
  DELIVERY_INVALID_STATE: '현재 배송 상태에서 허용되지 않는 처리입니다(배송완료·송장 정정은 배송중만).',
  DELIVERY_TRACKING_NO_CONFLICT: '다른 배송이 이미 사용 중인 송장번호입니다.',
  // 정산(Track 85)
  SETTLEMENT_NOT_FOUND: '정산을 찾을 수 없습니다(확정 전 정산은 조회되지 않습니다).',
}

/** 코드가 없거나 미지일 때 HTTP 상태별 폴백. */
const SELLER_STATUS_MESSAGES: Record<number, string> = {
  401: '로그인이 필요합니다.',
  403: '권한이 없습니다.',
  404: '대상을 찾을 수 없습니다.',
  409: '이미 처리됐거나 충돌하는 요청입니다. 최신 상태를 다시 확인하세요.',
  422: '현재 상태에서 허용되지 않는 처리입니다.',
}

const FALLBACK_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** ofetch 에러(FetchError)에서 ProblemDetail.code를 꺼낸다. 네트워크 오류·비JSON은 null. */
export function extractErrorCode(error: unknown): string | null {
  const code = (error as { data?: { code?: unknown } } | null)?.data?.code
  return typeof code === 'string' ? code : null
}

/** ofetch 에러에서 HTTP 상태를 꺼낸다(FetchError.status 또는 response.status). 없으면 null. */
export function extractErrorStatus(error: unknown): number | null {
  const candidate = error as { status?: unknown; response?: { status?: unknown } } | null
  const status = candidate?.status ?? candidate?.response?.status
  return typeof status === 'number' ? status : null
}

/** 정지 셀러 쓰기 거부(403 SELLER_SUSPENDED)인지 — 호출부가 토스트 문구·재조회 분기에 쓴다. */
export function isSellerSuspendedError(error: unknown): boolean {
  return extractErrorCode(error) === SELLER_SUSPENDED_ERROR_CODE
}

/** 코드 우선 → 상태 폴백 → 서버 detail → 일반 문구. */
export function toSellerErrorMessage(error: unknown): string {
  const code = extractErrorCode(error)
  if (code && SELLER_ERROR_MESSAGES[code]) return SELLER_ERROR_MESSAGES[code]
  const status = extractErrorStatus(error)
  if (status !== null && SELLER_STATUS_MESSAGES[status]) return SELLER_STATUS_MESSAGES[status]
  const detail = (error as { data?: { detail?: unknown } } | null)?.data?.detail
  return typeof detail === 'string' && detail !== '' ? detail : FALLBACK_MESSAGE
}

/** BE 400 VALIDATION_FAILED fieldErrors → 필드별 첫 메시지(관리자 admin-order-view.mapFieldErrors 복제). 없으면 빈 객체. */
export function mapFieldErrors(error: unknown): Record<string, string> {
  const fieldErrors = (error as { data?: { fieldErrors?: unknown } } | null)?.data?.fieldErrors
  if (!Array.isArray(fieldErrors)) return {}
  const mapped: Record<string, string> = {}
  for (const entry of fieldErrors as { field?: unknown; message?: unknown }[]) {
    if (typeof entry.field !== 'string' || entry.field in mapped) continue
    mapped[entry.field] = typeof entry.message === 'string' && entry.message !== '' ? entry.message : '입력값을 확인해 주세요.'
  }
  return mapped
}
