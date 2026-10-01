import type { AdminFaqItem, AdminFaqPrefill } from '#layers/admin/app/types/admin-faq'
import { FAQ_ANSWER_MAX, FAQ_QUESTION_MAX, type FaqCategory } from '~/lib/constants/faq'
import type { InquiryCategory } from '~/lib/constants/inquiry'

/** 문의 카테고리 → FAQ 카테고리(같은 값 4종 · 기타는 대응 없음 = 운영자 선택). switch라 문의 카테고리가 늘면 tsc가 멈춘다. */
function faqCategoryOf(category: InquiryCategory): FaqCategory | null {
  switch (category) {
    case 'ORDER_PAYMENT':
    case 'DELIVERY':
    case 'CLAIM':
    case 'ACCOUNT':
      return category
    case 'OTHER':
      return null
  }
}

/**
 * FAQ 후보 문의 → FAQ 등록 미리 채우기(D-253). 질문 = 문의 본문(200자 초과 절삭) · 답변 = 저장한 답변(2000자 초과 절삭) · 기타 문의는 카테고리
 * 미선택. 절삭·미선택마다 안내 1줄.
 */
export function faqPrefillFromInquiry(category: InquiryCategory, inquiryContent: string, answer: string): AdminFaqPrefill {
  const notices: string[] = []
  const faqCategory = faqCategoryOf(category)
  if (faqCategory === null) notices.push('기타 문의는 대응하는 FAQ 카테고리가 없어 직접 선택해야 합니다.')
  const question = inquiryContent.trim()
  if (question.length > FAQ_QUESTION_MAX) notices.push(`질문이 ${FAQ_QUESTION_MAX}자를 넘어 뒷부분을 잘랐습니다. 다듬어 주세요.`)
  const trimmedAnswer = answer.trim()
  if (trimmedAnswer.length > FAQ_ANSWER_MAX) notices.push(`답변이 ${FAQ_ANSWER_MAX}자를 넘어 뒷부분을 잘랐습니다. 다듬어 주세요.`)
  return {
    category: faqCategory,
    question: question.slice(0, FAQ_QUESTION_MAX),
    answer: trimmedAnswer.slice(0, FAQ_ANSWER_MAX),
    notices,
  }
}

/** 한 카테고리의 FAQ(숨김 포함)를 노출 순서(sortOrder → id)로. 정렬 요청 본문은 이 순서의 id 배열이다. */
export function faqsInCategory(items: AdminFaqItem[], category: FaqCategory): AdminFaqItem[] {
  return items
    .filter((item) => item.category === category)
    .sort((left, right) => left.sortOrder - right.sortOrder || left.id - right.id)
}

/** 수정 중 카테고리를 바꿨는지(바꾸면 저장 시 새 카테고리 끝으로 간다는 안내를 띄운다). */
export function faqCategoryMoved(original: AdminFaqItem | null, category: FaqCategory): boolean {
  return original !== null && original.category !== category
}
