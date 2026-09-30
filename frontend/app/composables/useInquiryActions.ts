import type { InquiryCategory } from '~/lib/constants/inquiry'
import { buildInquiryCreateBody, INQUIRY_RECENT_ORDER_SIZE } from '~/lib/constants/inquiry'
import type { MyInquiry } from '~/types/inquiry'
import type { OrderSummary, PagedResponse } from '~/types/order'

/**
 * 구매자 운영자 문의 쓰기(Track 106-4 · BUYER 전용 /v1/inquiries/**). 실패(RFC7807)는 throw해 호출부가 inquiry-error로 문구를 정한다.
 * 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해 string으로 고정한다(useProductQuestionActions 선례).
 */
export function useInquiryActions() {
  const api = useBuyerApi()

  function inquiryPath(inquiryId: string): string {
    return `/v1/inquiries/${inquiryId}`
  }

  /** 등록. 주문을 고르지 않았으면 orderId를 싣지 않는다. 201 {inquiryId}. */
  function create(category: InquiryCategory, content: string, orderId: string | null): Promise<{ inquiryId: string }> {
    return api<{ inquiryId: string }>('/v1/inquiries', { method: 'POST', body: buildInquiryCreateBody(category, content, orderId) })
  }

  /** 수정(카테고리·본문 · 미답변일 때만 · 아니면 422). 204. */
  function update(inquiryId: string, category: InquiryCategory, content: string): Promise<void> {
    return api<void>(inquiryPath(inquiryId), { method: 'PUT', body: { category, content: content.trim() } })
  }

  /** 삭제(미답변일 때만 · 아니면 422). 204. */
  function remove(inquiryId: string): Promise<void> {
    return api<void>(inquiryPath(inquiryId), { method: 'DELETE' })
  }

  /** 답변 확인(미확인 해제 · 이미 확인이면 무변경 204 · 미답변 422). */
  function checkAnswer(inquiryId: string): Promise<void> {
    const path: string = `${inquiryPath(inquiryId)}/answer-check`
    return api<void>(path, { method: 'PUT' })
  }

  return { create, update, remove, checkAnswer }
}

/** 내 문의 목록(GET /v1/inquiries/me · 최신순). page는 Ref로 받아 바뀌면 useFetch가 다시 부른다(useMyProductQuestions 선례). */
export function useMyInquiries(page: Ref<number>, size: number) {
  return useFetch<PagedResponse<MyInquiry>>('/v1/inquiries/me', {
    key: 'my-inquiries',
    $fetch: useBuyerApi(),
    query: { page, size },
  })
}

/**
 * 작성 화면의 최근 주문 선택지(GET /v1/orders 최근순 첫 페이지). 마이페이지 홈의 'recent-orders'(size 3)와 캐시를 나누려 key를 따로 둔다 —
 * 같은 key면 먼저 받은 쪽 건수로 다른 화면이 그려진다.
 */
export function useInquiryOrderOptions() {
  return useFetch<PagedResponse<OrderSummary>>('/v1/orders', {
    key: 'inquiry-order-options',
    $fetch: useBuyerApi(),
    query: { page: 0, size: INQUIRY_RECENT_ORDER_SIZE },
  })
}
