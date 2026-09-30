import type { InquiryCategory } from '~/lib/constants/inquiry'
import type { InquiryOrderOption } from '~/types/inquiry'

/**
 * pages/mypage/inquiries/new.vue → InquiryNewView(Track 106-4). category·content·orderId는 뷰가 v-model로 바꾸는 입력값이다(orderId ''는
 * "선택 안 함"). 취소·반품·교환(CLAIM)을 고르면 주문 화면에서 바로 신청할 수 있다는 안내와 링크(claimGuidePath)를 보여 주되 제출은 막지 않는다.
 * 제출·검증·실패 문구·성공 이동은 페이지가 한다.
 */
export interface InquiryNewPageVm {
  category: InquiryCategory | null
  content: string
  orderId: string
  orderOptions: InquiryOrderOption[]
  orderOptionsPending: boolean
  /** 최근 주문을 불러오지 못함(주문 없이 남기는 것은 가능). */
  orderOptionsFailed: boolean
  showClaimGuide: boolean
  claimGuidePath: string
  submitting: boolean
  errorText: string | null
  submit: () => Promise<void>
}
