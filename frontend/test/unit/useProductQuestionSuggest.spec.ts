import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { useProductQuestionSuggest } from '~/composables/useProductQuestionSuggest'
import type { ProductQuestionSuggestion } from '~/types/product-question'

/**
 * Track 106-2 즉시 답 composable: 입력이 멈춘 뒤 300ms에 한 번 · trim 후 2자 미만은 부르지 않음 · 늦게 도착한 이전 응답 폐기 ·
 * 실패는 카드만 비움(화면 오류 없음). 네트워크는 구매자 래퍼를 mock하고 시간은 가짜 타이머로 민다.
 */
const PRODUCT_ID = 'prd_TEST'
const SUGGEST_PATH = `/v1/products/${PRODUCT_ID}/questions/suggest`
const DEBOUNCE_MS = 300

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useBuyerApi', () => () => apiMock)

type SuggestApi = ReturnType<typeof useProductQuestionSuggest>

let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
  vi.useRealTimers()
})

async function mountSuggest(): Promise<SuggestApi> {
  let api: SuggestApi | null = null
  mounted = await mountSuspended(defineComponent({
    setup() {
      api = useProductQuestionSuggest(PRODUCT_ID)
      return () => h('div')
    },
  }))
  // debounce(setTimeout)만 가짜로 민다 — flushPromises가 쓰는 setImmediate까지 가짜로 두면 대기가 끝나지 않는다.
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  return api!
}

function card(text: string): ProductQuestionSuggestion {
  return { type: 'PRODUCT', text }
}

/** 호출된 q 값들. */
function queriedTexts(): unknown[] {
  const calls = apiMock.mock.calls as unknown as [string, { query: { q: string } }][]
  return calls.filter(([path]) => path === SUGGEST_PATH).map(([, options]) => options.query.q)
}

describe('useProductQuestionSuggest', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('연속 입력은 멈춘 뒤 300ms에 마지막 값으로 한 번만 부른다(trim)', async () => {
    apiMock.mockResolvedValue([card('세탁기 사용이 가능합니다.')])
    const suggest = await mountSuggest()

    suggest.onInput('세')
    suggest.onInput('세탁')
    suggest.onInput('  세탁 되나요  ')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS - 1)
    expect(apiMock).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(queriedTexts()).toEqual(['세탁 되나요'])
    expect(suggest.suggestions.value).toEqual([card('세탁기 사용이 가능합니다.')])
  })

  it('trim 후 2자 미만이면 부르지 않고 카드를 비운다', async () => {
    apiMock.mockResolvedValue([card('이전 카드')])
    const suggest = await mountSuggest()
    suggest.onInput('세탁')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(suggest.suggestions.value).toHaveLength(1)

    suggest.onInput('  세 ')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(queriedTexts()).toEqual(['세탁'])
    expect(suggest.suggestions.value).toEqual([])
  })

  it('늦게 도착한 이전 응답은 버리고 마지막 요청의 결과만 남긴다', async () => {
    let resolveFirst: (value: ProductQuestionSuggestion[]) => void = () => {}
    apiMock
      .mockImplementationOnce(() => new Promise<ProductQuestionSuggestion[]>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce([card('사이즈 결과')])
    const suggest = await mountSuggest()

    suggest.onInput('세탁 방법')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    suggest.onInput('사이즈')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(suggest.suggestions.value).toEqual([card('사이즈 결과')])

    resolveFirst([card('세탁 결과')])
    await flushPromises()
    expect(queriedTexts()).toEqual(['세탁 방법', '사이즈'])
    expect(suggest.suggestions.value).toEqual([card('사이즈 결과')])
  })

  it('실패하면 조용히 카드만 비운다(throw 없음)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    apiMock.mockResolvedValueOnce([card('이전 카드')]).mockRejectedValueOnce(new Error('network'))
    const suggest = await mountSuggest()
    suggest.onInput('세탁')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(suggest.suggestions.value).toHaveLength(1)

    suggest.onInput('세탁기')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(suggest.suggestions.value).toEqual([])
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})
