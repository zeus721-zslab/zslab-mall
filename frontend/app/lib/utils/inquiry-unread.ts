import type { MyInquiry } from '~/types/inquiry'

/**
 * 내 문의 미확인 해제 규칙(Track 106-4). 답변이 달린 미확인 문의를 펼칠 때만 확인 API를 부르고(같은 문의 요청 중이면 건너뜀), 성공한 문의는 목록을
 * 다시 받기 전까지 화면에서 미확인 표시를 지운다. 실패하면 표시를 그대로 둬 다음 펼침에 다시 부른다.
 */

/** 이 문의를 펼칠 때 확인 API를 불러야 하는지. */
export function shouldCheckAnswer(item: Pick<MyInquiry, 'unread' | 'answerContent'>, requesting: boolean): boolean {
  return item.unread && item.answerContent !== undefined && !requesting
}

/** 확인을 마친 문의의 unread를 false로 바꾼 목록(나머지는 그대로). */
export function withCheckedInquiries(items: MyInquiry[], checkedIds: readonly string[]): MyInquiry[] {
  return items.map((item) => (checkedIds.includes(item.inquiryId) ? { ...item, unread: false } : item))
}
