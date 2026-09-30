/**
 * 운영자 문의 상수 단일 소스(Track 106-4 · 4층위 enum 잠금의 FE 층). BE InquiryCategory·InquiryAnsweredFilter와 V43 chk_inquiry_category 값을
 * 그대로 옮긴다. 라벨 함수는 모르는 값이 와도 code를 그대로 보여 준다(BE가 값을 먼저 늘린 배포 순서 대비).
 */

/** 문의 카테고리(BE InquiryCategory · V43 chk_inquiry_category). 칩 순서 = 선언 순서. */
export type InquiryCategory = 'ORDER_PAYMENT' | 'DELIVERY' | 'CLAIM' | 'ACCOUNT' | 'OTHER'
export const INQUIRY_CATEGORIES: InquiryCategory[] = ['ORDER_PAYMENT', 'DELIVERY', 'CLAIM', 'ACCOUNT', 'OTHER']
export const INQUIRY_CATEGORY_LABELS: Record<InquiryCategory, string> = {
  ORDER_PAYMENT: '주문·결제',
  DELIVERY: '배송',
  CLAIM: '취소·반품·교환',
  ACCOUNT: '회원·계정',
  OTHER: '기타',
}
export function inquiryCategoryLabel(category: string): string {
  return INQUIRY_CATEGORY_LABELS[category as InquiryCategory] ?? category
}
export function isInquiryCategory(value: unknown): value is InquiryCategory {
  return typeof value === 'string' && INQUIRY_CATEGORIES.includes(value as InquiryCategory)
}

/** 관리자 목록 답변 여부 필터(BE InquiryAnsweredFilter · 기본 UNANSWERED). */
export type InquiryAnsweredFilter = 'UNANSWERED' | 'ANSWERED' | 'ALL'
export const INQUIRY_ANSWERED_FILTERS: InquiryAnsweredFilter[] = ['UNANSWERED', 'ANSWERED', 'ALL']
export const INQUIRY_ANSWERED_LABELS: Record<InquiryAnsweredFilter, string> = {
  UNANSWERED: '미답변',
  ANSWERED: '답변완료',
  ALL: '전체',
}
export function isInquiryAnsweredFilter(value: unknown): value is InquiryAnsweredFilter {
  return typeof value === 'string' && INQUIRY_ANSWERED_FILTERS.includes(value as InquiryAnsweredFilter)
}

/** 입력 한도(BE Inquiry.MIN/MAX_CONTENT_LENGTH · MAX_ANSWER_LENGTH). 본문은 trim 후 길이다. */
export const INQUIRY_CONTENT_MIN = 5
export const INQUIRY_CONTENT_MAX = 500
export const INQUIRY_ANSWER_MAX = 1000

/** 내 문의 한 번에 받는 수(BE 기본 size와 같다) · 작성 화면 최근 주문 선택지 수. */
export const INQUIRY_PAGE_SIZE = 10
export const INQUIRY_RECENT_ORDER_SIZE = 10

export const INQUIRY_LIST_PATH = '/mypage/inquiries'
export const INQUIRY_NEW_PATH = '/mypage/inquiries/new'
/** 작성 성공 후 내 문의에서 새 문의를 펼치게 넘기는 쿼리 키. */
export const INQUIRY_OPEN_QUERY = 'open'

/** 구매자 주문 public_id 형식(BE InquiryCreateRequest @Pattern과 같다 · ord_ + ULID 26자). */
const ORDER_PUBLIC_ID = /^ord_[0-9A-Z]{26}$/
const ORDER_DETAIL_PATH = /^\/orders\/(ord_[0-9A-Z]{26})\/?$/

export function isOrderPublicId(value: unknown): value is string {
  return typeof value === 'string' && ORDER_PUBLIC_ID.test(value)
}

/** 현재 경로가 구매자 주문 상세면 그 주문 id(아니면 null). */
export function orderIdFromPath(path: string): string | null {
  return ORDER_DETAIL_PATH.exec(path)?.[1] ?? null
}

/** 작성 화면 경로. 주문 id가 있으면 ?order로 붙인다(작성 화면이 자동 선택). */
export function inquiryNewPath(orderId: string | null): string {
  return orderId ? `${INQUIRY_NEW_PATH}?order=${orderId}` : INQUIRY_NEW_PATH
}

/** 등록 요청 본문. 주문을 고르지 않았으면 orderId 키를 싣지 않는다(빈 문자열은 BE 형식 위반 400). */
export function buildInquiryCreateBody(
  category: InquiryCategory,
  content: string,
  orderId: string | null,
): { category: InquiryCategory; content: string; orderId?: string } {
  const body: { category: InquiryCategory; content: string; orderId?: string } = { category, content: content.trim() }
  if (orderId) body.orderId = orderId
  return body
}
