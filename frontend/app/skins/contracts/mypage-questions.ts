import type { MyProductQuestion } from '~/types/product-question'
import type { ProductQuestionNotice } from './product-questions'

/**
 * pages/mypage/questions.vue → MypageQuestionsView(Track 106-2). page는 URL(?page=)이 단일 소스이고 이동은 movePage로만 한다(주문 내역 선례).
 * 수정·삭제 노출은 항목의 editable·deletable(서버 규칙 결과)을 따른다. 규칙 거절(422)·이미 삭제(404)면 안내 후 목록을 다시 읽는다.
 */
export interface MypageQuestionsPageVm {
  items: MyProductQuestion[]
  page: number
  hasNext: boolean
  movePage: (nextPage: number) => void
  pending: boolean
  error: Error | undefined
  retry: () => void
  notice: ProductQuestionNotice | null
  actionPendingId: string | null
  /** 수정. 성공하면 true(편집 닫기). */
  saveEdit: (questionId: string, content: string) => Promise<boolean>
  remove: (questionId: string) => Promise<void>
}
