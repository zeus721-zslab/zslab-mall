import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import type { InboxDeadlineTone, InboxItem } from '~/lib/inbox-view'
import { isInquiryCategory } from '~/lib/constants/inquiry'

/**
 * 관리자 인박스 표시 규칙(D-248 · 공용 규칙은 app/lib/inbox-view). 레이어 CSS 클래스와 관리자 다이얼로그 입력 변환만 둔다.
 */

const DEADLINE_CHIP_SUFFIX: Record<InboxDeadlineTone, string> = {
  overdue: 'danger',
  imminent: 'warning',
  normal: 'info',
  none: 'neutral',
}

export function adminInboxDeadlineChipClass(tone: InboxDeadlineTone): string {
  return `adm-chip adm-chip--${DEADLINE_CHIP_SUFFIX[tone]}`
}

/**
 * 1:1 문의 항목 → 답변 다이얼로그 입력(AdminInquiryListItem 필수 필드). 인박스 행은 title = 문의 본문 · subtitle = 카테고리 코드 · baseAt = 작성 시각
 * (BE InquiryUnansweredInboxSource)이다. 카테고리가 알 수 없는 값이면 null(답변 버튼을 숨긴다).
 */
export function inboxInquiryAnswerItem(item: InboxItem): AdminInquiryListItem | null {
  if (item.type !== 'INQUIRY_UNANSWERED' || !isInquiryCategory(item.subtitle)) return null
  return { inquiryId: item.sourceRef, category: item.subtitle, content: item.title, createdAt: item.baseAt ?? '' }
}
