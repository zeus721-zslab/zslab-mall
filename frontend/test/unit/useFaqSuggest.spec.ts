import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { useFaqSuggest } from '~/composables/useFaqSuggest'
import type { FaqItem } from '~/types/faq'

/**
 * Track 106-3 자유 입력 즉시 답(useProductQuestionSuggest 패턴): 멈춘 뒤 300ms에 한 번 · trim 후 2자 미만 무호출 · 앞 100자만 전송 ·
 * 늦은 응답 폐기 · 전송(search)은 바로 부르고 실패면 null. 네트워크는 구매자 래퍼를 mock하고 시간은 가짜 타이머로 민다.
 */
const SUGGEST_PATH = '/v1/faqs/suggest'
const DEBOUNCE_MS = 300

const { apiMock } = vi.hoisted(() => ({ apiMock: vi.fn() }))
mockNuxtImport('useBuyerApi', () => () => apiMock)

type SuggestApi = ReturnType<typeof useFaqSuggest>

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
      api = useFaqSuggest()
      return () => h('div')
    },
  }))
  // debounce(setTimeout)만 가짜로 민다 — flushPromises가 쓰는 setImmediate까지 가짜로 두면 대기가 끝나지 않는다.
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  return api!
}

function faq(id: number, question: string): FaqItem {
  return { id, category: 'DELIVERY', question, answer: `${question} 답` }
}

function queriedTexts(): unknown[] {
  const calls = apiMock.mock.calls as unknown as [string, { query: { q: string } }][]
  return calls.filter(([path]) => path === SUGGEST_PATH).map(([, options]) => options.query.q)
}

describe('useFaqSuggest', () => {
  beforeEach(() => {
    apiMock.mockReset()
  })

  it('연속 입력은 멈춘 뒤 300ms에 마지막 값(trim)으로 한 번만 부른다', async () => {
    apiMock.mockResolvedValue([faq(1, '송장번호는 어디서 볼 수 있나요?')])
    const suggest = await mountSuggest()

    suggest.onInput('송')
    suggest.onInput('  송장 어디서  ')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS - 1)
    expect(apiMock).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await flushPromises()
    expect(queriedTexts()).toEqual(['송장 어디서'])
    expect(suggest.suggestions.value).toHaveLength(1)
  })

  it('trim 후 2자 미만이면 부르지 않고 비운다 · 100자를 넘으면 앞 100자만 보낸다', async () => {
    apiMock.mockResolvedValue([faq(1, '질문')])
    const suggest = await mountSuggest()
    suggest.onInput('  배 ')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(apiMock).not.toHaveBeenCalled()
    expect(suggest.suggestions.value).toEqual([])

    suggest.onInput('가'.repeat(120))
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    expect(queriedTexts()).toEqual(['가'.repeat(100)])
  })

  it('늦게 도착한 이전 응답은 버린다', async () => {
    let resolveFirst: (value: FaqItem[]) => void = () => {}
    apiMock
      .mockImplementationOnce(() => new Promise<FaqItem[]>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce([faq(2, '반품 결과')])
    const suggest = await mountSuggest()

    suggest.onInput('배송 조회')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    suggest.onInput('반품 기한')
    await vi.advanceTimersByTimeAsync(DEBOUNCE_MS)
    await flushPromises()
    resolveFirst([faq(1, '배송 결과')])
    await flushPromises()
    expect(suggest.suggestions.value).toEqual([faq(2, '반품 결과')])
  })

  it('search: 바로 부르고 결과를 돌려준다 · 실패면 null(경고만) · 2자 미만은 호출 없이 빈 배열', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    apiMock.mockResolvedValueOnce([faq(1, '결과')]).mockRejectedValueOnce(new Error('network'))
    const suggest = await mountSuggest()

    await expect(suggest.search('  송장 어디서 ')).resolves.toEqual([faq(1, '결과')])
    await expect(suggest.search('반품 기한')).resolves.toBeNull()
    await expect(suggest.search(' 배 ')).resolves.toEqual([])
    expect(queriedTexts()).toEqual(['송장 어디서', '반품 기한'])
    expect(warn).toHaveBeenCalled()
    warn.mockRestore()
  })
})
