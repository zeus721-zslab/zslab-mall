import { useDebounceFn } from '@vueuse/core'
import { FAQ_SUGGEST_DEBOUNCE_MS, FAQ_SUGGEST_MAX, FAQ_SUGGEST_MIN } from '~/lib/constants/faq'
import type { FaqItem } from '~/types/faq'

/**
 * 자유 입력 즉시 답(Track 106-3 · GET /v1/faqs/suggest · 공개). useProductQuestionSuggest(106-2) 패턴: 입력이 멈춘 뒤 300ms에 부르고, trim 후 2자
 * 미만이면 부르지 않고 비운다 · 앞 100자만 보낸다 · 요청 번호로 늦게 도착한 이전 응답을 버린다. 입력 중 미리보기 실패는 화면에 알리지 않는다.
 * 전송(Enter)은 search()로 바로 불러 결과를 돌려준다 — 실패면 null(호출부가 오류 말풍선을 띄운다).
 */
export function useFaqSuggest() {
  const api = useBuyerApi()
  const suggestions = ref<FaqItem[]>([])
  let requestSequence = 0

  async function request(trimmed: string): Promise<FaqItem[]> {
    return api<FaqItem[]>('/v1/faqs/suggest', { query: { q: trimmed.slice(0, FAQ_SUGGEST_MAX) } })
  }

  async function fetchSuggestions(text: string): Promise<void> {
    const sequence = ++requestSequence
    const trimmed = text.trim()
    if (trimmed.length < FAQ_SUGGEST_MIN) {
      suggestions.value = []
      return
    }
    try {
      const result = await request(trimmed)
      if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
      suggestions.value = result
    } catch (error) {
      if (sequence !== requestSequence) return
      // 입력 중 미리보기는 보조 정보라 실패를 화면에 띄우지 않는다 — 원인만 남기고 비운다.
      console.warn('[faq] 즉시 답 미리보기 실패', error)
      suggestions.value = []
    }
  }

  const debouncedFetch = useDebounceFn(fetchSuggestions, FAQ_SUGGEST_DEBOUNCE_MS)

  /** 입력이 바뀔 때마다 부른다(실제 호출은 멈춘 뒤 한 번). */
  function onInput(text: string): void {
    void debouncedFetch(text)
  }

  /** 진행 중인 응답을 버리고, 대기 중인 호출은 빈 입력으로 덮어 미리보기가 되살아나지 않게 한다. */
  function clear(): void {
    requestSequence += 1
    suggestions.value = []
    void debouncedFetch('')
  }

  /** 전송 시 바로 검색한다(미리보기는 비운다). trim 후 2자 미만이면 빈 목록 · 실패면 null. */
  async function search(text: string): Promise<FaqItem[] | null> {
    clear()
    const trimmed = text.trim()
    if (trimmed.length < FAQ_SUGGEST_MIN) return []
    try {
      return await request(trimmed)
    } catch (error) {
      console.warn('[faq] 즉시 답 검색 실패', error)
      return null
    }
  }

  return { suggestions, onInput, clear, search }
}
