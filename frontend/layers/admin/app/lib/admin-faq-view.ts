import type { AdminFaqItem } from '#layers/admin/app/types/admin-faq'
import type { FaqCategory } from '~/lib/constants/faq'

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
