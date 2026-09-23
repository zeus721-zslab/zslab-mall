import type { OrderStatusCode } from '~/lib/constants/order'
import { PAYMENT_EXPIRE_MINUTES } from '~/lib/constants/order'

/**
 * 결제 재개 노출 판정(Track 102 FE-64·순수 함수·vitest 대상).
 *
 * 라운드 4 정찰: 결제대기 주문 상세에 "30분 내 결제되지 않으면 자동 취소됩니다" 안내만 있고 결제를 다시 시작할 진입점이 목록·상세 어디에도
 * 없었다(결제는 체크아웃 흐름에서만 시작됐다). BE에는 재결제(POST /api/v1/orders/{orderPublicId}/payments·D-60)가 이미 있어 그 경로를 쓴다.
 */

/** 결제를 다시 시작할 수 있는 주문 상태: 결제대기 하나뿐이다(미결제 종료는 주문이 이미 닫혔다). */
export function canResumePayment(statusCode: string): boolean {
  return statusCode === ('PENDING_PAYMENT' satisfies OrderStatusCode)
}

/** 미결제로 닫힌 주문 안내. 결제 버튼 대신 이 문구를 보여 "왜 결제할 수 없는지"를 말한다. */
export const PAYMENT_EXPIRED_NOTICE = `결제 시간(${PAYMENT_EXPIRE_MINUTES}분)이 지나 자동 취소된 주문입니다. 다시 구매하려면 장바구니에 담아 새로 주문해 주세요.`

/** 미결제 종료 안내를 띄울 상태. */
export function isPaymentExpired(statusCode: string): boolean {
  return statusCode === ('PAYMENT_EXPIRED' satisfies OrderStatusCode)
}

/**
 * 재결제 실패 응답(RFC7807) → 사용자 문구·후속 동작(Track 102 보완·순수 함수·vitest 대상).
 *
 * BE 실패 8종(BuyerOrderController → CheckoutService.retryPayment → PaymentService.initiate)을 실측해 매핑한다.
 * 보완 전에는 401만 분기하고 나머지를 422/그 외 두 갈래로 뭉개, 상품이 판매중지된 경우에도 "이미 결제되었거나 종료된 주문"이라고
 * 잘못 안내했다. 새 에러 체계를 만들지 않고 claim-request-error.ts와 같은 기능별 매핑 함수로만 처리한다.
 */
export interface PaymentResumeErrorLike {
  statusCode?: number
  data?: { code?: unknown; detail?: unknown }
}

/** 실패 처리 지시: 문구 + 주문 상세 재조회 필요 여부(상태가 이미 바뀐 실패는 다시 읽어 버튼 자체를 없앤다) + 로그인 유도 여부. */
export interface PaymentResumeFailure {
  message: string
  /** 주문 상태·상품 상태가 바뀐 실패 — 최신 상태를 다시 읽는다. */
  refresh: boolean
  /** 세션 만료 — 호출부가 로그인으로 보낸다(문구는 쓰이지 않는다). */
  login: boolean
}

function codeOf(error: PaymentResumeErrorLike): string {
  return typeof error.data?.code === 'string' ? error.data.code : ''
}

function detailOf(error: PaymentResumeErrorLike): string {
  return typeof error.data?.detail === 'string' ? error.data.detail : ''
}

/** ORDER_NOT_PAYABLE의 detail은 차단 사유 코드 그대로다(OrderNotPayableReason 2값·GlobalExceptionHandler:558). */
const NOT_PAYABLE_MESSAGES: Record<string, string> = {
  PRODUCT_NOT_ON_SALE: '주문한 상품이 판매 중지되어 결제할 수 없습니다. 주문을 취소하고 다른 상품을 담아 주세요.',
  OUT_OF_STOCK: '주문한 상품의 재고가 부족해 결제할 수 없습니다. 재고가 채워진 뒤 다시 시도하거나 주문을 취소해 주세요.',
}

const DEFAULT_MESSAGE = '결제를 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** 재결제 실패를 사용자 문구·후속 동작으로 바꾼다. 매핑에 없는 실패는 일반 문구 + 재조회 없음(재시도 가능). */
export function paymentResumeFailure(error: PaymentResumeErrorLike): PaymentResumeFailure {
  const statusCode = error.statusCode
  if (statusCode === 401) {
    return { message: DEFAULT_MESSAGE, refresh: false, login: true }
  }
  const code = codeOf(error)
  if (code === 'ORDER_NOT_FOUND') {
    // 미존재·타인 주문은 재시도해도 같은 결과다 — "잠시 후 다시" 대신 목록으로 보낸다.
    return { message: '주문을 찾을 수 없습니다. 주문 내역에서 다시 확인해 주세요.', refresh: false, login: false }
  }
  if (code === 'ORDER_NOT_PAYABLE') {
    return { message: NOT_PAYABLE_MESSAGES[detailOf(error)] ?? '지금은 이 주문을 결제할 수 없습니다. 주문한 상품의 판매 상태를 확인해 주세요.', refresh: true, login: false }
  }
  if (code === 'PAYMENT_ALREADY_COMPLETED') {
    return { message: '이미 결제가 완료된 주문입니다. 최신 상태를 확인해 주세요.', refresh: true, login: false }
  }
  if (code === 'ORDER_NOT_PENDING_PAYMENT') {
    return { message: '결제할 수 없는 상태의 주문입니다(이미 결제되었거나 취소·종료됨). 최신 상태를 확인해 주세요.', refresh: true, login: false }
  }
  if (code === 'PAYMENT_IN_PROGRESS') {
    // 30분 안에 시작한 결제가 살아 있다 — 새 시도를 막는 것이 정상이라 "기다렸다 다시"를 알린다.
    return { message: `진행 중인 결제가 있습니다. ${PAYMENT_EXPIRE_MINUTES}분이 지나 만료되면 다시 시도할 수 있습니다.`, refresh: false, login: false }
  }
  return { message: DEFAULT_MESSAGE, refresh: false, login: false }
}
