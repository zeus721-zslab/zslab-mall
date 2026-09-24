import { IRREVERSIBLE, riskConfirmMessage } from '~/lib/utils/risk-confirm'

/**
 * 주문 상태 라벨 단일 소스(FE-12a·FE-12c·BE OrderStatus enum 9값 대응·CLAUDE.md 4층위 enum 잠금 (4)프론트).
 * BE는 StatusView.label=code(한글 미제공)로 내려주므로 code→한글 매핑은 FE가 담당한다. 유니온 타입으로 매직 문자열을 막는다.
 * PAYMENT_EXPIRED(미결제 종료)는 목록 API에서 제외되어 통상 노출되지 않으나, 라벨은 방어적으로 정의한다(FE-12c).
 */

/** 주문 상태 code(BE OrderStatus enum 9값). */
export type OrderStatusCode =
  | 'PENDING_PAYMENT'
  | 'PAID'
  | 'PREPARING'
  | 'SHIPPING'
  | 'DELIVERED'
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'PARTIAL_CANCEL'
  | 'PAYMENT_EXPIRED'

/** code→한글 라벨 매핑(표시 문구 확정). */
export const ORDER_STATUS_LABELS: Record<OrderStatusCode, string> = {
  PENDING_PAYMENT: '결제대기',
  PAID: '결제완료',
  PREPARING: '상품준비중',
  SHIPPING: '배송중',
  DELIVERED: '배송완료',
  CONFIRMED: '구매확정',
  CANCELLED: '취소',
  PARTIAL_CANCEL: '부분취소',
  PAYMENT_EXPIRED: '미결제 종료',
}

/** 상태 code를 한글 라벨로 변환한다. 매핑에 없는 code는 원본 code를 그대로 폴백 반환한다(방어). */
export function orderStatusLabel(code: string): string {
  return ORDER_STATUS_LABELS[code as OrderStatusCode] ?? code
}

/**
 * 주문 상세 안내 문구의 기간 값(Track 96-1 FE-53·C-16). 시스템이 실제로 보장하는 기간만 문구로 쓰며 값은 BE 설정과 일치해야 한다
 * (BE 변경 시 함께 갱신).
 * - AUTO_CONFIRM_DAYS: backend delivery/service/ReturnWindowPolicy.java:22 WINDOW_DAYS = 7 (order/scheduler/OrderAutoConfirmScheduler.java:53 사용)
 * - PAYMENT_EXPIRE_MINUTES: backend payment/service/PaymentService.java:57 PENDING_TTL = 30분 (order/scheduler/OrderAutoCancelScheduler.java:42 GRACE_MINUTES 동일)
 */
export const AUTO_CONFIRM_DAYS = 7
export const PAYMENT_EXPIRE_MINUTES = 30

/**
 * 구매확정 확인 패널 경고(Track 102 FE-64 위험 조작 문구 규약 — 무엇이 일어나는지 + 가역성).
 * 구매확정은 되돌릴 수 없고, 되돌리고 싶어도 반품·교환 요청 경로가 닫힌다.
 */
export const ITEM_CONFIRM_WARNING = riskConfirmMessage(
  '확정하면 이 품목의 반품·교환을 더 이상 요청할 수 없습니다.',
  IRREVERSIBLE,
)

/** 구매확정 확인창 설명 첫 줄(FE-80). 둘째 줄이 대상 품목이다({@link itemConfirmTarget}). */
export const ITEM_CONFIRM_TARGET_CAPTION = '구매 확정할 품목'

/**
 * 구매확정 확인창 설명 둘째 줄 = 대상 품목만(FE-79·FE-80). 결과(ITEM_CONFIRM_WARNING)는 확인창 안내로 따로 보인다.
 * 조사를 붙이지 않는다 — 옵션 라벨 끝 글자에 따라 "사이즈: M을"처럼 어색해진다(FE-79 캡처 관찰). 상품명·옵션은 표시용 선택 값이라
 * 둘 다 없으면 "이 품목"으로 쓴다.
 */
export function itemConfirmTarget(productName: string | null | undefined, optionLabel: string | null | undefined): string {
  const target = [productName, optionLabel].filter((part): part is string => typeof part === 'string' && part.trim() !== '').join(' · ')
  return target === '' ? '이 품목' : target
}

/** 배송완료 품목 아래 안내(구매확정 버튼 옆). */
export const AUTO_CONFIRM_GUIDE = `배송완료 ${AUTO_CONFIRM_DAYS}일 후 자동 구매확정됩니다.`
/** 결제 대기 주문 헤더 아래 안내. */
export const PAYMENT_EXPIRE_GUIDE = `${PAYMENT_EXPIRE_MINUTES}분 내 결제되지 않으면 주문이 자동 취소됩니다.`
