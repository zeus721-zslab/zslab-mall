/**
 * FAQ 채팅 도우미 상수 단일 소스(Track 106-3 · 4층위 enum 잠금의 FE 층). 카테고리는 BE FaqCategory 선언 순서 = 칩 순서다(V42 chk_faq_category).
 * 라벨 함수는 모르는 값이 와도 code를 그대로 보여 준다(BE가 값을 먼저 늘린 배포 순서 대비).
 */

export type FaqCategory = 'ORDER_PAYMENT' | 'DELIVERY' | 'CLAIM' | 'ACCOUNT' | 'REVIEW_QUESTION'
export const FAQ_CATEGORIES: FaqCategory[] = ['ORDER_PAYMENT', 'DELIVERY', 'CLAIM', 'ACCOUNT', 'REVIEW_QUESTION']
export const FAQ_CATEGORY_LABELS: Record<FaqCategory, string> = {
  ORDER_PAYMENT: '주문·결제',
  DELIVERY: '배송',
  CLAIM: '취소·반품·교환',
  ACCOUNT: '회원·계정',
  REVIEW_QUESTION: '리뷰·문의',
}
export function faqCategoryLabel(category: string): string {
  return FAQ_CATEGORY_LABELS[category as FaqCategory] ?? category
}
export function isFaqCategory(value: unknown): value is FaqCategory {
  return typeof value === 'string' && FAQ_CATEGORIES.includes(value as FaqCategory)
}

/** 자유 입력 즉시 답: trim 후 이 길이 이상일 때만 부른다(BE q 2~100자 · 넘는 입력은 앞 100자만 보낸다) · 입력 뒤 대기 시간. */
export const FAQ_SUGGEST_MIN = 2
export const FAQ_SUGGEST_MAX = 100
export const FAQ_SUGGEST_DEBOUNCE_MS = 300

/** 관리자 입력 한도(BE FaqWriteRequest @Size · V42 컬럼 길이). */
export const FAQ_QUESTION_MAX = 200
export const FAQ_ANSWER_MAX = 2000

/** 대화 유지: 탭 동안(sessionStorage) · 오래된 말풍선부터 버려 이 수를 넘기지 않는다. */
export const FAQ_CONVERSATION_STORAGE_KEY = 'zslab-faq-assistant'
export const FAQ_CONVERSATION_MAX_MESSAGES = 50
/** 도우미 답 말풍선 앞 짧은 타이핑 표시 시간(동작 줄이기면 생략). */
export const FAQ_TYPING_DELAY_MS = 450

/** 도우미를 그리지 않는 경로: 모의 결제(외부 결제창 역할) · 리뷰 작성·수정(하단 고정 등록 버튼과 겹침). */
const EXCLUDED_PATTERNS: RegExp[] = [/^\/payment\/mock$/, /^\/reviews\/new$/, /^\/reviews\/[^/]+\/edit$/]

/** 페이지별로 먼저 보여 줄(강조할) 카테고리. 위에서부터 첫 일치 규칙을 쓴다. */
const FIRST_CATEGORY_RULES: { pattern: RegExp; categories: FaqCategory[] }[] = [
  { pattern: /^\/products\/[^/]+$/, categories: ['DELIVERY', 'CLAIM'] },
  { pattern: /^\/(cart|checkout|checkout\/complete)$/, categories: ['ORDER_PAYMENT'] },
  { pattern: /^\/orders(\/[^/]+)?$/, categories: ['DELIVERY', 'CLAIM'] },
  { pattern: /^\/claims(\/.*)?$/, categories: ['CLAIM'] },
  { pattern: /^\/mypage\/questions$/, categories: ['REVIEW_QUESTION'] },
  { pattern: /^\/(login|signup|mypage|mypage\/(profile|addresses|password|withdraw))$/, categories: ['ACCOUNT'] },
]

function normalizePath(path: string): string {
  return path.length > 1 && path.endsWith('/') ? path.slice(0, -1) : path
}

export function isFaqAssistantExcluded(path: string): boolean {
  const normalized = normalizePath(path)
  return EXCLUDED_PATTERNS.some((pattern) => pattern.test(normalized))
}

/** 이 페이지에서 먼저 보여 줄 카테고리(없으면 빈 배열 — 홈·목록·검색·도움말·에러 화면 등). */
export function faqFirstCategories(path: string): FaqCategory[] {
  const normalized = normalizePath(path)
  return FIRST_CATEGORY_RULES.find((rule) => rule.pattern.test(normalized))?.categories ?? []
}

/** 칩 순서: 첫 칩 카테고리를 앞에 두고 나머지는 선언 순서. */
export function orderedFaqCategories(firstCategories: FaqCategory[]): FaqCategory[] {
  return [...firstCategories, ...FAQ_CATEGORIES.filter((category) => !firstCategories.includes(category))]
}
