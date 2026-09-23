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
  // 주문·발송(D-191·prepare-shipment)
  ORDER_NOT_FOUND: '주문 품목을 찾을 수 없습니다(내 품목이 아니거나 미결제 주문).',
  ORDER_ITEM_INVALID_STATE: '현재 품목 상태에서 허용되지 않는 처리입니다(발송은 결제완료 품목만).',
  CLAIM_STATE_INVALID: '클레임이 진행 중인 품목은 발송할 수 없습니다.',
  // 배송(D-191·mark-delivered·송장 정정)
  DELIVERY_NOT_FOUND: '배송을 찾을 수 없습니다(내 배송이 아니거나 삭제됨).',
  DELIVERY_INVALID_STATE: '현재 배송 상태에서 허용되지 않는 처리입니다(배송완료·송장 정정은 배송중만).',
  DELIVERY_TRACKING_NO_CONFLICT: '다른 배송이 이미 사용 중인 송장번호입니다.',
  // 클레임 조회(Track 90-D-1)
  CLAIM_NOT_FOUND: '클레임을 찾을 수 없습니다(내 품목의 클레임이 아니거나 삭제됨).',
  // 정산(Track 85)
  SETTLEMENT_NOT_FOUND: '정산을 찾을 수 없습니다(확정 대기 정산은 조회되지 않습니다).',
  // 상품·재고(Track 90-C·GlobalExceptionHandler 코드명 1:1)
  PRODUCT_NOT_FOUND: '상품을 찾을 수 없습니다(내 상품이 아니거나 삭제됨).',
  PRODUCT_VARIANT_NOT_FOUND: '상품 옵션(변형)을 찾을 수 없습니다(내 상품이 아니거나 삭제됨).',
  CATEGORY_NOT_FOUND: '카테고리를 찾을 수 없습니다.',
  PRODUCT_VARIANT_OPTION_CONFLICT: '같은 옵션 조합의 변형이 이미 있습니다.',
  PRODUCT_IMAGE_NOT_FOUND: '상품 이미지를 찾을 수 없습니다.',
  // 판매 상태 셀프 전환(Track 96-5·D-206)
  PRODUCT_INVALID_STATE: '현재 상품 상태에서 허용되지 않는 전환입니다(승인대기·거부됨 상품은 전환 불가·같은 상태 재요청). 최신 상태를 다시 확인하세요.',
  PRODUCT_STOPPED_BY_ADMIN: '관리자가 판매중지한 상품은 셀러가 재판매할 수 없습니다. 운영자에게 문의하세요.',
  INVENTORY_INVARIANT_VIOLATION: '재고 수량이 맞지 않습니다(출고량이 보유·가용 재고를 초과하거나 재고 행이 없음).',
  // 정산계좌(Track 90-D-3·D-199)
  SELLER_OWNER_REQUIRED: '정산계좌 등록은 셀러 대표(OWNER)만 할 수 있습니다.',
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

/**
 * 원인이 여러 가지인데 코드가 하나뿐이라 코드 문구가 원인을 지워 버리는 코드(Track 101-A·관리자 admin-error-message와 같은 규칙).
 * BE가 detail에 실어 보낸 구체 사유를 코드 문구보다 먼저 쓰고, detail이 비어 있으면 기존대로 코드 문구로 폴백한다.
 */
const DETAIL_FIRST_CODES: ReadonlySet<string> = new Set(['CLAIM_STATE_INVALID'])

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

/** DETAIL_FIRST_CODES는 서버 detail 우선 → 코드 우선 → 상태 폴백 → 서버 detail → 일반 문구. */
export function toSellerErrorMessage(error: unknown): string {
  const code = extractErrorCode(error)
  const detail = (error as { data?: { detail?: unknown } } | null)?.data?.detail
  const detailText = typeof detail === 'string' && detail !== '' ? detail : null
  if (code && detailText && DETAIL_FIRST_CODES.has(code)) return detailText
  if (code && SELLER_ERROR_MESSAGES[code]) return SELLER_ERROR_MESSAGES[code]
  const status = extractErrorStatus(error)
  if (status !== null && SELLER_STATUS_MESSAGES[status]) return SELLER_STATUS_MESSAGES[status]
  return detailText ?? FALLBACK_MESSAGE
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
