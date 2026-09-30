/**
 * 운영자 문의 API 타입(Track 106-4 · BE inquiry/controller/response 대응). 응답은 전역 NON_NULL이라 null 필드는 키가 빠진다 — 그런 필드는
 * 선택(?)으로 둔다(orderId·orderNo·answerContent·answeredAt).
 */
import type { InquiryCategory } from '~/lib/constants/inquiry'

/** 작성 화면 첨부 주문 선택지(value = 주문 public_id · label = 주문번호·대표 상품·주문일 · 화면 전용). */
export interface InquiryOrderOption {
  orderId: string
  label: string
}

/** 내 문의 항목(BE MyInquiryResponse). editable·deletable(미답변)·unread(답변 미확인)는 서버 규칙의 결과다. */
export interface MyInquiry {
  inquiryId: string
  category: InquiryCategory
  content: string
  orderId?: string
  orderNo?: string
  answerContent?: string
  answeredAt?: string
  editable: boolean
  deletable: boolean
  unread: boolean
  createdAt: string
}
