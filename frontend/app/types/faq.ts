/**
 * FAQ API 타입(Track 106-3 · BE faq/controller/response 대응). 공개 목록과 즉시 답이 같은 모양이다.
 */
import type { FaqCategory } from '~/lib/constants/faq'

/** 구매자 FAQ 항목(BE FaqResponse). */
export interface FaqItem {
  id: number
  category: FaqCategory
  question: string
  answer: string
}
