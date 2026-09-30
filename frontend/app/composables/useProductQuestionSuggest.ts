import { useDebounceFn } from '@vueuse/core'
import {
  PRODUCT_QUESTION_SUGGEST_DEBOUNCE_MS,
  PRODUCT_QUESTION_SUGGEST_MAX,
  PRODUCT_QUESTION_SUGGEST_MIN,
} from '~/lib/constants/product-question'
import type { ProductQuestionSuggestion } from '~/types/product-question'

/**
 * 질문 입력 중 즉시 답(Track 106-2 · GET /v1/products/{p}/questions/suggest · 공개). 입력이 멈춘 뒤 300ms에 부르고, trim 후 2자 미만이면 부르지 않고
 * 카드를 비운다. 요청 번호로 늦게 도착한 이전 응답을 버린다(셀러 클레임 목록 requestSequence 선례). 실패는 화면에 알리지 않는다 — 카드만 비우고
 * 등록 버튼은 그대로라 질문 흐름을 막지 않는다.
 */
export function useProductQuestionSuggest(productPublicId: string) {
  const api = useBuyerApi()
  // 템플릿 리터럴 경로는 nitro 타입드 라우트 추론이 과도해 string으로 고정한다(useReviewActions 선례).
  const path: string = `/v1/products/${productPublicId}/questions/suggest`
  const suggestions = ref<ProductQuestionSuggestion[]>([])
  let requestSequence = 0

  async function fetchSuggestions(text: string): Promise<void> {
    const sequence = ++requestSequence
    const trimmed = text.trim()
    if (trimmed.length < PRODUCT_QUESTION_SUGGEST_MIN) {
      suggestions.value = []
      return
    }
    try {
      const result = await api<ProductQuestionSuggestion[]>(path, { query: { q: trimmed.slice(0, PRODUCT_QUESTION_SUGGEST_MAX) } })
      if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
      suggestions.value = result
    } catch (error) {
      if (sequence !== requestSequence) return
      // 즉시 답은 보조 정보라 실패를 화면에 띄우지 않는다 — 원인만 남기고 카드를 비운다.
      console.warn('[product-question] 즉시 답 조회 실패', error)
      suggestions.value = []
    }
  }

  const debouncedFetch = useDebounceFn(fetchSuggestions, PRODUCT_QUESTION_SUGGEST_DEBOUNCE_MS)

  /** 입력이 바뀔 때마다 부른다(실제 호출은 멈춘 뒤 한 번). */
  function onInput(text: string): void {
    void debouncedFetch(text)
  }

  /** 등록 뒤처럼 입력을 비울 때: 진행 중인 응답을 버리고, 아직 대기 중인 호출은 빈 입력으로 덮어 카드가 되살아나지 않게 한다. */
  function clear(): void {
    requestSequence += 1
    suggestions.value = []
    void debouncedFetch('')
  }

  return { suggestions, onInput, clear }
}
