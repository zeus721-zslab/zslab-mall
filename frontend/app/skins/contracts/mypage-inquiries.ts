import type { InquiryCategory } from '~/lib/constants/inquiry'
import type { MyInquiry } from '~/types/inquiry'

/** 문의 화면 안내 띠(RenewNotice tone과 같다). */
export interface InquiryNotice {
  tone: 'success' | 'info' | 'warning' | 'danger'
  text: string
}

/**
 * pages/mypage/inquiries/index.vue → MypageInquiriesView(Track 106-4). page는 URL(?page=)이 단일 소스이고 이동은 movePage로만 한다(내 질문 선례).
 * 펼침 상태는 뷰가 들고, 답변이 있는 미확인 문의를 펼치면 뷰가 checkAnswer를 부른다(성공하면 unread가 false가 된다 · 실패는 다음 펼침에 재시도).
 * 수정·삭제 노출은 항목의 editable·deletable(서버 규칙 결과)을 따른다. 규칙 거절(422)·이미 삭제(404)면 안내 후 목록을 다시 읽는다.
 */
export interface MypageInquiriesPageVm {
  items: MyInquiry[]
  page: number
  hasNext: boolean
  movePage: (nextPage: number) => void
  pending: boolean
  error: Error | undefined
  retry: () => void
  notice: InquiryNotice | null
  /** 작성 직후 이동이면 그 문의를 펼친 채 보여 준다(없으면 null). */
  initialOpenId: string | null
  newInquiryPath: string
  actionPendingId: string | null
  /** 미확인 답변 확인 처리(답변 없음·이미 확인이면 아무것도 하지 않는다). */
  checkAnswer: (item: MyInquiry) => void
  /** 수정. 성공하면 true(편집 닫기). */
  saveEdit: (inquiryId: string, category: InquiryCategory, content: string) => Promise<boolean>
  remove: (inquiryId: string) => Promise<void>
}
