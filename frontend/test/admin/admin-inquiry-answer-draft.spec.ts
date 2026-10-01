import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import AdminInquiryAnswerDialog from '#layers/admin/app/components/admin/AdminInquiryAnswerDialog.vue'
import { faqPrefillFromInquiry } from '#layers/admin/app/lib/admin-faq-view'

/**
 * 관리자 문의 답변 다이얼로그의 답안 초안 + FAQ 후보(D-253 · FE-107): 초안 조회·근거·"초안 사용" · FAQ 후보 체크 → 답변 저장 성공 후 미리 채운 FAQ
 * 등록 다이얼로그 → FAQ 저장·취소 뒤 done · 체크 안 하면 바로 done · 미리 채우기 절삭·카테고리 매핑. API·토스트는 mock.
 */
const { inquiriesApiMock, faqsApiMock, toastMock } = vi.hoisted(() => ({
  inquiriesApiMock: { list: vi.fn(), answer: vi.fn(), answerDraft: vi.fn() },
  faqsApiMock: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), reorder: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/admin/app/composables/useAdminInquiries', () => ({ useAdminInquiries: () => inquiriesApiMock }))
vi.mock('#layers/admin/app/composables/useAdminFaqs', () => ({ useAdminFaqs: () => faqsApiMock }))
vi.mock('#layers/admin/app/composables/useAdminToast', () => ({ useAdminToast: () => toastMock }))

const DRAFT = '안녕하세요, 고객님. 배송 관련 문의 주셔서 감사합니다.\n\n- 주문 상태: 배송중'
const ORDER_ONLY_DRAFT = { draft: DRAFT, evidence: [{ kind: 'ORDER', title: '주문 20261001-A', summary: '주문 상태: 배송중' }], faqCandidate: true }

function item(overrides: Record<string, unknown> = {}) {
  return { inquiryId: 'inq_1', category: 'DELIVERY', content: '배송이 언제 오나요?', createdAt: '2026-10-01T10:00:00+09:00', ...overrides }
}

async function mountDialog(props: Record<string, unknown>) {
  const wrapper = await mountSuspended(AdminInquiryAnswerDialog, {
    props: { open: false, item: null, ...props }, global: { plugins: [createVuetify()] }, attachTo: document.body,
  })
  await wrapper.setProps({ open: true })
  await flushPromises()
  return wrapper
}

function query<T extends Element>(selector: string): T | null {
  return document.body.querySelector<T>(selector)
}

async function click(testId: string): Promise<void> {
  const element = query<HTMLElement>(`[data-testid="${testId}"]`)
  if (!element) throw new Error(`${testId} 없음`)
  element.click()
  await flushPromises()
}

describe('AdminInquiryAnswerDialog — 답안 초안·FAQ 후보(D-253)', () => {
  beforeEach(() => {
    Object.values(inquiriesApiMock).forEach((fn) => fn.mockReset())
    Object.values(faqsApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('초안 조회 · 기존 답변 유지 → "초안 사용"으로만 채움 · FAQ 근거 있으면 FAQ 후보 표시 없음', async () => {
    inquiriesApiMock.answerDraft.mockResolvedValueOnce({ ...ORDER_ONLY_DRAFT, faqCandidate: false })
    await mountDialog({ item: item({ answerContent: '기존 답변' }) })

    expect(inquiriesApiMock.answerDraft).toHaveBeenCalledWith('inq_1')
    expect(query('[data-testid="answer-draft-evidence"]')?.textContent).toContain('[주문] 주문 20261001-A')
    expect(query<HTMLTextAreaElement>('[data-testid="answer-content"] textarea')?.value).toBe('기존 답변')
    expect(query('[data-testid="answer-faq-candidate"]')).toBeNull()

    await click('answer-draft-use')
    expect(query<HTMLTextAreaElement>('[data-testid="answer-content"] textarea')?.value).toBe(DRAFT)
  })

  it('FAQ 후보 + 등록 체크 → 답변 저장 성공 후 미리 채운 FAQ 등록 다이얼로그 → FAQ 저장 뒤 done 1회', async () => {
    inquiriesApiMock.answerDraft.mockResolvedValueOnce(ORDER_ONLY_DRAFT)
    inquiriesApiMock.answer.mockResolvedValueOnce(undefined)
    faqsApiMock.create.mockResolvedValueOnce({ id: 77 })
    const wrapper = await mountDialog({ item: item() })

    expect(query('[data-testid="answer-faq-candidate"]')?.textContent).toContain('FAQ 후보')
    await click('answer-draft-use')
    query<HTMLInputElement>('[data-testid="answer-faq-register"] input')?.click()
    await flushPromises()
    expect(query<HTMLInputElement>('[data-testid="answer-faq-register"] input')?.checked).toBe(true)
    await click('answer-dialog-ok')

    expect(inquiriesApiMock.answer).toHaveBeenCalledWith('inq_1', DRAFT)
    expect(wrapper.emitted('done')).toBeUndefined()
    expect(query<HTMLInputElement>('[data-testid="faq-question"] input')?.value).toBe('배송이 언제 오나요?')
    expect(query<HTMLTextAreaElement>('[data-testid="faq-answer"] textarea')?.value).toBe(DRAFT)

    await click('faq-dialog-ok')
    expect(faqsApiMock.create).toHaveBeenCalledWith({ category: 'DELIVERY', question: '배송이 언제 오나요?', answer: DRAFT, visible: true })
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('FAQ 등록 취소 → done 1회(FAQ 미등록) · 등록 체크 안 하면 답변 저장 즉시 done', async () => {
    inquiriesApiMock.answerDraft.mockResolvedValue(ORDER_ONLY_DRAFT)
    inquiriesApiMock.answer.mockResolvedValue(undefined)
    const unchecked = await mountDialog({ item: item() })
    await click('answer-draft-use')
    await click('answer-dialog-ok')
    expect(unchecked.emitted('done')).toHaveLength(1)
    expect(query('[data-testid="admin-faq-dialog"]')).toBeNull()
    unchecked.unmount()
    document.body.innerHTML = ''

    const checked = await mountDialog({ item: item() })
    await click('answer-draft-use')
    const checkbox = query<HTMLInputElement>('[data-testid="answer-faq-register"] input')
    checkbox?.click()
    await flushPromises()
    expect(checkbox?.checked).toBe(true)
    await click('answer-dialog-ok')
    expect(checked.emitted('done')).toBeUndefined()
    await click('faq-dialog-cancel')
    expect(faqsApiMock.create).not.toHaveBeenCalled()
    expect(checked.emitted('done')).toHaveLength(1)
  })

  it('초안 조회 실패 → 안내 1줄 · FAQ 후보 표시 없음(토스트 없음) · 답변은 그대로 저장 → done', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    inquiriesApiMock.answerDraft.mockRejectedValueOnce({ status: 500, data: { code: 'INTERNAL_ERROR' } })
    inquiriesApiMock.answer.mockResolvedValueOnce(undefined)
    const wrapper = await mountDialog({ item: item({ answerContent: '기존 답변' }) })

    expect(query('[data-testid="answer-draft-failed"]')).not.toBeNull()
    expect(query('[data-testid="answer-faq-candidate"]')).toBeNull()
    expect(toastMock.danger).not.toHaveBeenCalled()
    await click('answer-dialog-ok')
    expect(inquiriesApiMock.answer).toHaveBeenCalledWith('inq_1', '기존 답변')
    expect(wrapper.emitted('done')).toHaveLength(1)
  })

  it('기타 문의 → FAQ 카테고리 미선택(저장 버튼 비활성 + 안내) · 질문 200자 절삭 안내', async () => {
    inquiriesApiMock.answerDraft.mockResolvedValueOnce(ORDER_ONLY_DRAFT)
    inquiriesApiMock.answer.mockResolvedValueOnce(undefined)
    await mountDialog({ item: item({ category: 'OTHER', content: '가'.repeat(250) }) })
    await click('answer-draft-use')
    const checkbox = query<HTMLInputElement>('[data-testid="answer-faq-register"] input')
    checkbox?.click()
    await flushPromises()
    await click('answer-dialog-ok')

    const notices = Array.from(document.body.querySelectorAll('[data-testid="faq-prefill-notice"]')).map((element) => element.textContent)
    expect(notices).toEqual(['기타 문의는 대응하는 FAQ 카테고리가 없어 직접 선택해야 합니다.', '질문이 200자를 넘어 뒷부분을 잘랐습니다. 다듬어 주세요.'])
    expect(query<HTMLInputElement>('[data-testid="faq-question"] input')?.value).toHaveLength(200)
    expect(query<HTMLButtonElement>('[data-testid="faq-dialog-ok"]')?.disabled).toBe(true)
  })
})

describe('faqPrefillFromInquiry', () => {
  it('같은 카테고리 4종은 그대로 · 기타는 null · 2000자 초과 답변 절삭 안내 · 앞뒤 공백 제거', () => {
    for (const category of ['ORDER_PAYMENT', 'DELIVERY', 'CLAIM', 'ACCOUNT'] as const) {
      expect(faqPrefillFromInquiry(category, ' 질문 ', '답').category).toBe(category)
    }
    const other = faqPrefillFromInquiry('OTHER', ' 질문 ', ' 답 ')
    expect(other).toEqual({ category: null, question: '질문', answer: '답', notices: ['기타 문의는 대응하는 FAQ 카테고리가 없어 직접 선택해야 합니다.'] })
    const longAnswer = faqPrefillFromInquiry('CLAIM', '질문', '나'.repeat(2100))
    expect(longAnswer.answer).toHaveLength(2000)
    expect(longAnswer.notices).toEqual(['답변이 2000자를 넘어 뒷부분을 잘랐습니다. 다듬어 주세요.'])
  })
})
