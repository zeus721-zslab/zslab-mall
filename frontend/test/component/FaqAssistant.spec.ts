import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import FaqAssistant from '~/components/faq/FaqAssistant.vue'
import { FAQ_CONVERSATION_STORAGE_KEY } from '~/lib/constants/faq'
import type { FaqItem } from '~/types/faq'

/**
 * Track 106-3 채팅 도우미 흐름: 열기 → 인사·카테고리 칩 → 질문 칩 → 답 말풍선 · 자유 입력 0건 → 찾지 못했어요 + 카테고리 칩 · 목록 실패 → 다시 시도 ·
 * 저장된 대화 복원(깨진 값이면 새 대화). 네트워크는 구매자 래퍼 mock · 타이핑 지연은 동작 줄이기(matchMedia)로 생략한다.
 * Track 106-4: 0건·답 말풍선 뒤 "운영자에게 남기기" 칩(검색 오류 말풍선에는 없음) → 작성 화면 이동(주문 상세면 ?order).
 */
const FAQS: FaqItem[] = [
  { id: 1, category: 'DELIVERY', question: '송장번호는 어디서 볼 수 있나요?', answer: '주문 상세에서 확인할 수 있습니다.' },
  { id: 2, category: 'DELIVERY', question: '구매확정은 무엇인가요?', answer: '상품을 잘 받았음을 확정하는 절차입니다.' },
  { id: 3, category: 'CLAIM', question: '반품은 언제까지 요청할 수 있나요?', answer: '배송완료 후 7일 이내입니다.' },
]

const { apiMock, navigateToMock, routePath } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  navigateToMock: vi.fn(),
  // 칩을 누르는 시점의 경로(Track 106-4 · 주문 상세면 ?order를 붙인다). 테스트가 바꾼다.
  routePath: { value: '/' },
}))
mockNuxtImport('useBuyerApi', () => () => apiMock)
mockNuxtImport('navigateTo', () => navigateToMock)
mockNuxtImport('useRoute', () => () => ({
  get path() {
    return routePath.value
  },
}))

let wrapper: VueWrapper | null = null

beforeEach(() => {
  apiMock.mockReset()
  navigateToMock.mockReset()
  routePath.value = '/'
  clearNuxtState()
  window.sessionStorage.clear()
  vi.spyOn(window, 'matchMedia').mockImplementation((query: string) => ({
    matches: query.includes('prefers-reduced-motion'),
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }))
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.restoreAllMocks()
})

function respondWith(list: FaqItem[] | Error, suggest: FaqItem[] = []): void {
  apiMock.mockImplementation((path: string) => {
    if (path === '/v1/faqs') return list instanceof Error ? Promise.reject(list) : Promise.resolve(list)
    if (path === '/v1/faqs/suggest') return Promise.resolve(suggest)
    return Promise.reject(new Error(`unexpected ${path}`))
  })
}

async function mountAndOpen(): Promise<VueWrapper> {
  wrapper = await mountSuspended(FaqAssistant, { attachTo: document.body })
  await wrapper.get('[data-testid="faq-launcher"]').trigger('click')
  await flushPromises()
  return wrapper
}

function bubbles(target: VueWrapper): string[] {
  return target.findAll('[data-testid="faq-bubble"]').map((bubble) => bubble.text())
}

function chipTexts(target: VueWrapper): string[] {
  return target.findAll('[data-testid="faq-chip"]').map((chip) => chip.text())
}

describe('FaqAssistant', () => {
  it('인사·카테고리 5개 → 카테고리 → 질문 칩 → 답 말풍선 + 다른 질문·처음으로', async () => {
    respondWith(FAQS)
    const target = await mountAndOpen()
    expect(bubbles(target)[0]).toContain('무엇을 도와드릴까요')
    expect(target.findAll('[data-testid="faq-category-chip"]')).toHaveLength(5)

    await target.findAll('[data-testid="faq-category-chip"]').find((chip) => chip.text() === '배송')!.trigger('click')
    await flushPromises()
    expect(bubbles(target).slice(-2)).toEqual(['배송', '배송 관련 자주 묻는 질문이에요.'])
    expect(chipTexts(target)).toEqual(['송장번호는 어디서 볼 수 있나요?', '구매확정은 무엇인가요?'])

    await target.findAll('[data-testid="faq-chip"]')[0]!.trigger('click')
    await flushPromises()
    expect(bubbles(target).slice(-2)).toEqual(['송장번호는 어디서 볼 수 있나요?', '주문 상세에서 확인할 수 있습니다.'])
    expect(chipTexts(target)).toEqual(['배송 다른 질문', '운영자에게 남기기', '처음으로'])
    expect(apiMock.mock.calls.filter(([path]) => path === '/v1/faqs')).toHaveLength(1)
  })

  it('자유 입력 0건 → 찾지 못했어요 + 카테고리 칩 · 결과가 있으면 질문 칩', async () => {
    respondWith(FAQS, [])
    const target = await mountAndOpen()
    const input = target.get('[data-testid="faq-input"]')

    await input.setValue('환율 문의')
    await target.get('form').trigger('submit')
    await flushPromises()
    expect(bubbles(target).slice(-2)).toEqual(['환율 문의', '찾지 못했어요. 아래 주제에서 찾아보시겠어요?'])
    expect(target.findAll('[data-testid="faq-category-chip"]')).toHaveLength(5)
    expect(chipTexts(target)).toEqual(['운영자에게 남기기'])

    respondWith(FAQS, [FAQS[2]!])
    await input.setValue('반품 기한')
    await target.get('form').trigger('submit')
    await flushPromises()
    expect(bubbles(target).at(-1)).toBe('이런 질문을 찾았어요.')
    expect(chipTexts(target)).toEqual(['반품은 언제까지 요청할 수 있나요?'])
  })

  it('검색 실패(오류) 말풍선에는 운영자에게 남기기 칩이 없다(재시도 유도)', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    apiMock.mockImplementation((path: string) => {
      if (path === '/v1/faqs') return Promise.resolve(FAQS)
      return Promise.reject(new Error('network'))
    })
    const target = await mountAndOpen()
    await target.get('[data-testid="faq-input"]').setValue('환율 문의')
    await target.get('form').trigger('submit')
    await flushPromises()
    expect(bubbles(target).at(-1)).toContain('지금은 검색하지 못했어요')
    expect(chipTexts(target)).toEqual([])
    expect(warn).toHaveBeenCalled()
  })

  it('운영자에게 남기기 칩 → 작성 화면으로 이동 · 주문 상세 경로면 그 주문을 ?order로 붙인다', async () => {
    const navigate = navigateToMock
    respondWith(FAQS, [])
    routePath.value = '/orders/ord_01KXE2E0000000000000000001'
    const target = await mountAndOpen()
    await target.get('[data-testid="faq-input"]').setValue('환율 문의')
    await target.get('form').trigger('submit')
    await flushPromises()
    await target.get('[data-testid="faq-chip"][data-kind="inquiry"]').trigger('click')
    await flushPromises()
    expect(navigate).toHaveBeenCalledWith('/mypage/inquiries/new?order=ord_01KXE2E0000000000000000001')

    routePath.value = '/products/prd_01KX'
    await target.get('[data-testid="faq-chip"][data-kind="inquiry"]').trigger('click')
    await flushPromises()
    expect(navigate).toHaveBeenLastCalledWith('/mypage/inquiries/new')
  })

  it('목록 조회 실패 → 오류 말풍선 + 다시 시도 칩 → 다시 시도 성공 시 인사', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    respondWith(new Error('network'))
    const target = await mountAndOpen()
    expect(bubbles(target)).toEqual(['자주 묻는 질문을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'])
    expect(chipTexts(target)).toEqual(['다시 시도'])

    respondWith(FAQS)
    await target.get('[data-testid="faq-chip"]').trigger('click')
    await flushPromises()
    expect(bubbles(target).at(-1)).toContain('무엇을 도와드릴까요')
    expect(warn).toHaveBeenCalled()
  })

  it('저장된 대화·열림 상태를 복원한다 · 깨진 값이면 새 대화로 시작하고 지운다', async () => {
    window.sessionStorage.setItem(FAQ_CONVERSATION_STORAGE_KEY, JSON.stringify({
      open: true,
      messages: [{ id: 1, role: 'assistant', text: '이전 대화', chips: [] }],
    }))
    wrapper = await mountSuspended(FaqAssistant)
    await flushPromises()
    expect(wrapper.find('[data-testid="faq-panel"]').exists()).toBe(true)
    expect(bubbles(wrapper)).toEqual(['이전 대화'])
    wrapper.unmount()
    clearNuxtState()

    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    window.sessionStorage.setItem(FAQ_CONVERSATION_STORAGE_KEY, '{깨진 JSON')
    wrapper = await mountSuspended(FaqAssistant)
    await flushPromises()
    expect(wrapper.find('[data-testid="faq-panel"]').exists()).toBe(false)
    expect(window.sessionStorage.getItem(FAQ_CONVERSATION_STORAGE_KEY)).toBeNull()
    expect(warn).toHaveBeenCalled()
  })

  it('저장소 접근이 막혀도(throw) 새 대화로 동작한다', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    // 사이트 데이터 차단 브라우저처럼 저장소 접근 자체가 throw한다.
    vi.spyOn(window, 'sessionStorage', 'get').mockImplementation(() => { throw new Error('SecurityError') })
    respondWith(FAQS)
    const target = await mountAndOpen()
    expect(bubbles(target)[0]).toContain('무엇을 도와드릴까요')
    expect(warn).toHaveBeenCalled()
  })
})
