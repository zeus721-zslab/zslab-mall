/**
 * 상품 Q&A API 타입(Track 106-2 · BE productquestion/controller/response 대응). 응답은 전역 NON_NULL이라 null 필드는 키가 빠진다 —
 * 그런 필드는 선택(?)으로 둔다(answerContent·answeredAt·writtenByMe·hiddenReason·productPublicId 등).
 */
import type { ProductQuestionStatus, ProductQuestionSuggestionType } from '~/lib/constants/product-question'

/** 공개 질문 목록 항목(BE ProductQuestionItemResponse). 작성자 정보는 없다 · writtenByMe는 구매자 쿠키 조회에서만 온다. */
export interface ProductQuestionItem {
  questionId: string
  content: string
  answerContent?: string
  answeredAt?: string
  writtenByMe?: boolean
  createdAt: string
}

/** 즉시 답 항목(BE ProductQuestionSuggestionResponse). answer는 QNA만, id는 QNA·REVIEW만 있다. */
export interface ProductQuestionSuggestion {
  type: ProductQuestionSuggestionType
  text: string
  answer?: string
  id?: string
}

/** 내 질문 항목(BE MyProductQuestionResponse · 숨김 포함). editable·deletable은 서버 상태 규칙의 결과다. 상품이 삭제되면 상품 필드가 없다. */
export interface MyProductQuestion {
  questionId: string
  productPublicId?: string
  productName?: string
  content: string
  status: ProductQuestionStatus
  hiddenReason?: string
  answerContent?: string
  answeredAt?: string
  editable: boolean
  deletable: boolean
  createdAt: string
}
