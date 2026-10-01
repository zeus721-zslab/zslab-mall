import type { SellerProductQuestionItem } from '#layers/seller/app/types/seller-product-question'
import type { InboxDeadlineTone, InboxItem } from '~/lib/inbox-view'

/**
 * 셀러 인박스 표시 규칙(D-248 · 관리자 admin-inbox-view 복제 · 레이어 격리). 공용 규칙은 app/lib/inbox-view이고 여기는 셀러 CSS 클래스와 셀러
 * 다이얼로그 입력 변환만 둔다.
 */

const DEADLINE_CHIP_SUFFIX: Record<InboxDeadlineTone, string> = {
  overdue: 'danger',
  imminent: 'warning',
  normal: 'info',
  none: 'neutral',
}

export function sellerInboxDeadlineChipClass(tone: InboxDeadlineTone): string {
  return `slr-chip slr-chip--${DEADLINE_CHIP_SUFFIX[tone]}`
}

/**
 * Q&A 미답변 항목 → 답변 다이얼로그 입력. 인박스 행은 title = 질문 본문 · subtitle = 상품명 · baseAt = 작성 시각(BE QuestionUnansweredInboxSource).
 */
export function inboxQuestionAnswerItem(item: InboxItem): SellerProductQuestionItem | null {
  if (item.type !== 'QUESTION_UNANSWERED') return null
  const question: SellerProductQuestionItem = { questionId: item.sourceRef, content: item.title, createdAt: item.baseAt ?? '' }
  if (item.subtitle) question.productName = item.subtitle
  return question
}
