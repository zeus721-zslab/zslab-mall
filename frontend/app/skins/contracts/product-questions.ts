import type { ProductQuestionItem, ProductQuestionSuggestion } from '~/types/product-question'

/** 섹션 알림 한 줄(등록 결과·수정·삭제 거절 안내). */
export interface ProductQuestionNotice {
  tone: 'success' | 'info' | 'warning' | 'danger'
  text: string
}

/**
 * 상품 상세 "이 상품, 물어보세요" 섹션 vm(Track 106-2 · ProductDetailPageVm.questions). 대화형: 입력(draft)이 바뀌면 즉시 답 카드(suggestions)가
 * 갱신되고, 해결되지 않으면 submit으로 공개 질문을 등록한다(비로그인·비구매자는 로그인으로 보낸다). 공개 목록 첫 페이지는 SSR, 더보기·수정·삭제는
 * 브라우저에서 한다. 수정·삭제는 writtenByMe인 미답변 질문만 노출하고, BE가 규칙상 거절(422)하면 안내 후 목록을 다시 읽는다.
 */
export interface ProductQuestionsVm {
  draft: string
  setDraft: (text: string) => void
  suggestions: ProductQuestionSuggestion[]
  submitting: boolean
  submit: () => Promise<void>
  notice: ProductQuestionNotice | null
  items: ProductQuestionItem[]
  totalCount: number
  pending: boolean
  failed: boolean
  retry: () => Promise<void>
  hasNext: boolean
  loadingMore: boolean
  loadMore: () => Promise<void>
  /** 수정·삭제 진행 중인 질문 id(버튼 잠금). */
  actionPendingId: string | null
  /** 본인 미답변 질문 수정. 성공하면 true(편집 닫기). */
  saveEdit: (questionId: string, content: string) => Promise<boolean>
  remove: (questionId: string) => Promise<void>
}
