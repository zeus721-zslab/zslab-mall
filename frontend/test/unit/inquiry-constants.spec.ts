import { describe, it, expect } from 'vitest'
import {
  INQUIRY_CATEGORIES,
  buildInquiryCreateBody,
  inquiryCategoryLabel,
  inquiryNewPath,
  isInquiryAnsweredFilter,
  isInquiryCategory,
  isOrderPublicId,
  orderIdFromPath,
} from '~/lib/constants/inquiry'
import { inquiryChangeErrorMessage, inquiryCreateErrorMessage, isStaleInquiryError, validateInquiryContent } from '~/lib/utils/inquiry-error'
import { ATTACHED_ORDER_FALLBACK_LABEL, buildInquiryOrderOptions } from '~/lib/utils/inquiry-order-options'
import { shouldCheckAnswer, withCheckedInquiries } from '~/lib/utils/inquiry-unread'
import type { MyInquiry } from '~/types/inquiry'
import type { OrderSummary } from '~/types/order'

/**
 * Track 106-4 운영자 문의 FE 순수 규칙: 카테고리 단일 소스(BE InquiryCategory · V43 CHECK) · 주문 상세 경로 → 주문 id · 작성 화면 경로 · 요청 본문
 * (주문 미선택이면 orderId 키 없음) · 입력 검사·실패 문구 · 최근 주문 선택지 · 미확인 해제 판정.
 */
const ORDER_ID = 'ord_01KXE2E0000000000000000001'

function inquiry(overrides: Partial<MyInquiry> = {}): MyInquiry {
  return {
    inquiryId: 'inq_01KXE2E0000000000000000001', category: 'DELIVERY', content: '배송이 언제 시작되나요?', editable: true, deletable: true,
    unread: false, createdAt: '2026-09-30T12:00:00.000+09:00', ...overrides,
  }
}

function order(orderId: string, orderNo?: string): OrderSummary {
  return {
    orderId, orderNo, previewTitle: '면 티셔츠 외 1건', sellerCount: 1, totalPrice: 19000, status: { code: 'PAID', label: '결제완료' },
    orderedAt: '2026-09-28T10:00:00.000+09:00', activeClaims: [],
  } as OrderSummary
}

describe('카테고리 단일 소스', () => {
  it('BE 선언 순서 5종 · 라벨 · 모르는 값은 code 그대로', () => {
    expect(INQUIRY_CATEGORIES).toEqual(['ORDER_PAYMENT', 'DELIVERY', 'CLAIM', 'ACCOUNT', 'OTHER'])
    expect(INQUIRY_CATEGORIES.map(inquiryCategoryLabel)).toEqual(['주문·결제', '배송', '취소·반품·교환', '회원·계정', '기타'])
    expect(inquiryCategoryLabel('REVIEW_QUESTION')).toBe('REVIEW_QUESTION')
    expect(isInquiryCategory('OTHER')).toBe(true)
    expect(isInquiryCategory('REVIEW_QUESTION')).toBe(false)
    expect(isInquiryAnsweredFilter('UNANSWERED')).toBe(true)
    expect(isInquiryAnsweredFilter('unanswered')).toBe(false)
  })
})

describe('주문 id · 작성 화면 경로', () => {
  it('구매자 주문 상세 경로에서만 주문 id를 꺼낸다(끝 슬래시 허용)', () => {
    expect(orderIdFromPath(`/orders/${ORDER_ID}`)).toBe(ORDER_ID)
    expect(orderIdFromPath(`/orders/${ORDER_ID}/`)).toBe(ORDER_ID)
    expect(orderIdFromPath('/orders')).toBeNull()
    expect(orderIdFromPath(`/admin/orders/${ORDER_ID}`)).toBeNull()
    expect(orderIdFromPath('/orders/ord_short')).toBeNull()
    expect(orderIdFromPath(`/orders/${ORDER_ID}/claims`)).toBeNull()
  })

  it('작성 화면 경로는 주문 id가 있을 때만 ?order를 붙인다 · ?order 형식 검사', () => {
    expect(inquiryNewPath(ORDER_ID)).toBe(`/mypage/inquiries/new?order=${ORDER_ID}`)
    expect(inquiryNewPath(null)).toBe('/mypage/inquiries/new')
    expect(isOrderPublicId(ORDER_ID)).toBe(true)
    expect(isOrderPublicId('ord_abc')).toBe(false)
    expect(isOrderPublicId(['ord_x'])).toBe(false)
  })
})

describe('등록 요청 본문', () => {
  it('주문 미선택이면 orderId 키가 없다(빈 문자열 금지) · 본문 trim', () => {
    expect(buildInquiryCreateBody('OTHER', '  문의 내용입니다  ', null)).toEqual({ category: 'OTHER', content: '문의 내용입니다' })
    expect('orderId' in buildInquiryCreateBody('OTHER', '문의 내용입니다', null)).toBe(false)
    expect(buildInquiryCreateBody('CLAIM', '반품하고 싶어요', ORDER_ID)).toEqual({ category: 'CLAIM', content: '반품하고 싶어요', orderId: ORDER_ID })
  })
})

describe('입력 검사 · 실패 문구', () => {
  it('trim 후 5~500자', () => {
    expect(validateInquiryContent('   네글자임   ')).toContain('5자 이상')
    expect(validateInquiryContent('다섯글자임')).toBeNull()
    expect(validateInquiryContent('가'.repeat(501))).toContain('500자까지')
  })

  it('ORDER_NOT_FOUND는 주문 재선택 안내 · INQUIRY_INVALID_STATE·INQUIRY_NOT_FOUND는 재조회 대상', () => {
    const orderMissing = { statusCode: 404, data: { code: 'ORDER_NOT_FOUND' } }
    const answered = { statusCode: 422, data: { code: 'INQUIRY_INVALID_STATE' } }
    const deleted = { statusCode: 404, data: { code: 'INQUIRY_NOT_FOUND' } }
    expect(inquiryCreateErrorMessage(orderMissing)).toContain('선택한 주문을 찾을 수 없어요')
    expect(inquiryChangeErrorMessage(answered)).toContain('답변이 달린 문의')
    expect(inquiryChangeErrorMessage(deleted)).toContain('이미 삭제된 문의')
    expect(isStaleInquiryError(answered)).toBe(true)
    expect(isStaleInquiryError(deleted)).toBe(true)
    expect(isStaleInquiryError({ statusCode: 500 })).toBe(false)
  })
})

describe('최근 주문 선택지', () => {
  it('주문번호 · 대표 상품 · 주문일 · ?order 주문이 목록 밖이면 맨 앞에 넣는다', () => {
    const other = 'ord_01KXE2E0000000000000000002'
    const options = buildInquiryOrderOptions([order(other, 'ORD-20260928-0001')], ORDER_ID)
    expect(options.map((option) => option.orderId)).toEqual([ORDER_ID, other])
    expect(options[0]!.label).toBe(ATTACHED_ORDER_FALLBACK_LABEL)
    expect(options[1]!.label).toBe('ORD-20260928-0001 · 면 티셔츠 외 1건 · 2026.09.28')
    expect(buildInquiryOrderOptions([order(ORDER_ID)], ORDER_ID).map((option) => option.label)).toEqual(['면 티셔츠 외 1건 · 2026.09.28'])
  })
})

describe('미확인 해제 판정', () => {
  it('답변이 있고 미확인이며 요청 중이 아닐 때만 확인한다', () => {
    expect(shouldCheckAnswer(inquiry({ unread: true, answerContent: '답변' }), false)).toBe(true)
    expect(shouldCheckAnswer(inquiry({ unread: true, answerContent: '답변' }), true)).toBe(false)
    expect(shouldCheckAnswer(inquiry({ unread: false, answerContent: '답변' }), false)).toBe(false)
    expect(shouldCheckAnswer(inquiry({ unread: true }), false)).toBe(false)
  })

  it('확인을 마친 문의만 unread를 지운다', () => {
    const first = inquiry({ inquiryId: 'inq_A', unread: true, answerContent: '답변' })
    const second = inquiry({ inquiryId: 'inq_B', unread: true, answerContent: '답변' })
    expect(withCheckedInquiries([first, second], ['inq_A']).map((item) => item.unread)).toEqual([false, true])
  })
})
