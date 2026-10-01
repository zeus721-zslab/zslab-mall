import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { createVuetify } from 'vuetify'
import { flushPromises } from '@vue/test-utils'
import SellerProductQuestionAnswerDialog from '#layers/seller/app/components/seller/SellerProductQuestionAnswerDialog.vue'

/**
 * 셀러 Q&A 답변 다이얼로그의 답안 초안(D-253 · FE-107): 열 때 초안 조회 · 근거 표시 · 기존 답변은 자동으로 덮어쓰지 않고 "초안 사용"으로만 채운다 ·
 * 근거 없음 안내 · 조회 실패 안내 1줄 + 답변은 그대로 가능. API·토스트는 mock.
 */
const { questionsApiMock, toastMock } = vi.hoisted(() => ({
  questionsApiMock: { list: vi.fn(), answer: vi.fn(), answerDraft: vi.fn() },
  toastMock: { success: vi.fn(), warning: vi.fn(), danger: vi.fn(), info: vi.fn(), show: vi.fn() },
}))
vi.mock('#layers/seller/app/composables/useSellerProductQuestions', () => ({ useSellerProductQuestions: () => questionsApiMock }))
vi.mock('#layers/seller/app/composables/useSellerToast', () => ({ useSellerToast: () => toastMock }))

const DRAFT = '안녕하세요, 고객님.\n\n- 이전 답변 요약'
const DRAFT_RESPONSE = {
  draft: DRAFT,
  evidence: [{ kind: 'ANSWERED_QUESTION', title: '사이즈가 어떤가요?', summary: '정사이즈입니다.' }],
  faqCandidate: false,
}

function item(answerContent?: string) {
  return { questionId: 'pqn_1', productName: '반찬통', content: '사이즈 문의', answerContent, createdAt: '2026-10-01T10:00:00+09:00' }
}

async function mountDialog(props: Record<string, unknown>) {
  const wrapper = await mountSuspended(SellerProductQuestionAnswerDialog, {
    props: { open: false, item: null, ...props }, global: { plugins: [createVuetify()] }, attachTo: document.body,
  })
  await wrapper.setProps({ open: true })
  await flushPromises()
  return wrapper
}

function textarea(): HTMLTextAreaElement {
  const element = document.body.querySelector<HTMLTextAreaElement>('[data-testid="answer-content"] textarea')
  if (!element) throw new Error('답변 입력란 없음')
  return element
}

async function click(testId: string): Promise<void> {
  const element = document.body.querySelector<HTMLElement>(`[data-testid="${testId}"]`)
  if (!element) throw new Error(`${testId} 없음`)
  element.click()
  await flushPromises()
}

describe('SellerProductQuestionAnswerDialog — 답안 초안(D-253)', () => {
  beforeEach(() => {
    Object.values(questionsApiMock).forEach((fn) => fn.mockReset())
    Object.values(toastMock).forEach((fn) => fn.mockReset())
    document.body.innerHTML = ''
    vi.stubGlobal('visualViewport', { width: 1280, height: 800, scale: 1, offsetLeft: 0, offsetTop: 0, addEventListener: () => {}, removeEventListener: () => {} })
  })

  it('열면 초안 조회 · 근거 표시 · 기존 답변 유지 → "초안 사용"을 눌러야 입력란이 초안으로 바뀐다', async () => {
    questionsApiMock.answerDraft.mockResolvedValueOnce(DRAFT_RESPONSE)
    await mountDialog({ item: item('기존 답변') })

    expect(questionsApiMock.answerDraft).toHaveBeenCalledWith('pqn_1')
    expect(document.body.querySelectorAll('[data-testid="answer-draft-evidence"]')).toHaveLength(1)
    expect(document.body.querySelector('[data-testid="answer-draft-evidence"]')?.textContent).toContain('[이전 답변] 사이즈가 어떤가요?')
    expect(textarea().value).toBe('기존 답변')

    await click('answer-draft-use')
    expect(textarea().value).toBe(DRAFT)
  })

  it('근거 없음(draft null) → 직접 작성 안내 · 초안 사용 버튼 없음', async () => {
    questionsApiMock.answerDraft.mockResolvedValueOnce({ draft: null, evidence: [], faqCandidate: false })
    await mountDialog({ item: item() })
    expect(document.body.querySelector('[data-testid="answer-draft-empty"]')?.textContent).toContain('근거 부족')
    expect(document.body.querySelector('[data-testid="answer-draft-use"]')).toBeNull()
  })

  it('초안 조회 실패 → 안내 1줄(토스트 없음) · 답변 제출은 그대로 done', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    questionsApiMock.answerDraft.mockRejectedValueOnce({ status: 500, data: { code: 'INTERNAL_ERROR' } })
    questionsApiMock.answer.mockResolvedValueOnce(undefined)
    const wrapper = await mountDialog({ item: item() })

    expect(document.body.querySelector('[data-testid="answer-draft-failed"]')).not.toBeNull()
    expect(toastMock.danger).not.toHaveBeenCalled()
    textarea().value = '직접 쓴 답변'
    textarea().dispatchEvent(new Event('input'))
    await flushPromises()
    await click('answer-dialog-ok')
    expect(questionsApiMock.answer).toHaveBeenCalledWith('pqn_1', '직접 쓴 답변')
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
})
