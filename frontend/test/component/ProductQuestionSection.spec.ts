import { describe, it, expect, vi, afterEach } from 'vitest'
import { reactive } from 'vue'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { VueWrapper } from '@vue/test-utils'
import ProductQuestionSection from '~/skins/renew/components/ProductQuestionSection.vue'
import type { ProductQuestionsVm } from '~/skins/contracts/product-questions'
import type { ProductQuestionItem } from '~/types/product-question'

/**
 * Track 106-2 상품 상세 묻기 섹션: 즉시 답 카드 유형 라벨(Q&A·리뷰·상품 설명) · 입력이 있을 때만 등록 버튼 · 미답변 "답변 대기" ·
 * 수정·삭제는 내가 쓴 미답변 질문에만 · 0건이면 "첫 질문을 남겨 보세요".
 */
let mounted: VueWrapper | null = null
afterEach(() => {
  mounted?.unmount()
  mounted = null
})

function question(overrides: Partial<ProductQuestionItem> = {}): ProductQuestionItem {
  return { questionId: 'pqn_1', content: '세탁기 되나요?', createdAt: '2026-09-20T12:00:00.000+09:00', ...overrides }
}

function vmWith(overrides: Partial<ProductQuestionsVm>): ProductQuestionsVm {
  return reactive({
    draft: '',
    setDraft: vi.fn(),
    suggestions: [],
    submitting: false,
    submit: vi.fn(),
    notice: null,
    items: [],
    totalCount: 0,
    pending: false,
    failed: false,
    retry: vi.fn(),
    hasNext: false,
    loadingMore: false,
    loadMore: vi.fn(),
    actionPendingId: null,
    saveEdit: vi.fn(),
    remove: vi.fn(),
    ...overrides,
  }) as ProductQuestionsVm
}

async function mountSection(vm: ProductQuestionsVm): Promise<VueWrapper> {
  mounted = await mountSuspended(ProductQuestionSection, { props: { questions: vm } })
  return mounted
}

describe('ProductQuestionSection', () => {
  it('즉시 답 카드: 유형 라벨 3종 · Q&A는 답변 포함 · 입력이 있으면 등록 버튼', async () => {
    const wrapper = await mountSection(vmWith({
      draft: '세탁',
      suggestions: [
        { type: 'QNA', text: '세탁 방법이 궁금해요', answer: '찬물 단독 세탁', id: 'pqn_9' },
        { type: 'REVIEW', text: '세탁해도 줄지 않아요', id: 'rvw_9' },
        { type: 'PRODUCT', text: '세탁기 사용이 가능합니다.' },
      ],
    }))

    const cards = wrapper.findAll('[data-testid="product-question-suggestion"]')
    expect(cards.map((card) => card.find('[data-testid="suggestion-type"]').text())).toEqual(['Q&A', '리뷰', '상품 설명'])
    expect(cards[0]!.find('[data-testid="suggestion-answer"]').text()).toContain('찬물 단독 세탁')
    expect(cards[1]!.find('[data-testid="suggestion-answer"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="product-question-submit"]').text()).toBe('해결되지 않았어요 → 셀러에게 질문 남기기')
  })

  it('입력이 비어 있으면 카드·등록 버튼이 없다', async () => {
    const wrapper = await mountSection(vmWith({ draft: '   ' }))
    expect(wrapper.find('[data-testid="product-question-suggestions"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="product-question-submit"]').exists()).toBe(false)
  })

  it('목록: 미답변 "답변 대기" · 답변은 A로 표시 · 수정·삭제는 내가 쓴 미답변 질문에만', async () => {
    const wrapper = await mountSection(vmWith({
      totalCount: 3,
      items: [
        question({ questionId: 'pqn_mine', writtenByMe: true }),
        question({ questionId: 'pqn_mine_answered', writtenByMe: true, answerContent: '가능합니다', answeredAt: '2026-09-21T09:00:00.000+09:00' }),
        question({ questionId: 'pqn_other', writtenByMe: false }),
      ],
    }))

    const items = wrapper.findAll('[data-testid="product-question-item"]')
    expect(items).toHaveLength(3)
    expect(items[0]!.find('[data-testid="question-waiting"]').text()).toBe('답변 대기')
    expect(items[0]!.find('[data-testid="question-actions"]').exists()).toBe(true)
    expect(items[1]!.find('[data-testid="question-waiting"]').exists()).toBe(false)
    expect(items[1]!.find('[data-testid="question-answer"]').text()).toContain('가능합니다')
    expect(items[1]!.find('[data-testid="question-actions"]').exists()).toBe(false)
    expect(items[2]!.find('[data-testid="question-waiting"]').exists()).toBe(true)
    expect(items[2]!.find('[data-testid="question-actions"]').exists()).toBe(false)
  })

  it('삭제는 확인 뒤에만 vm.remove를 부른다 · 수정 저장은 vm.saveEdit', async () => {
    const vm = vmWith({ totalCount: 1, items: [question({ questionId: 'pqn_mine', writtenByMe: true })] })
    vi.mocked(vm.saveEdit).mockResolvedValue(true)
    const wrapper = await mountSection(vm)

    await wrapper.find('[data-testid="question-delete"]').trigger('click')
    expect(vm.remove).not.toHaveBeenCalled()
    await wrapper.find('[data-testid="question-delete-confirm"]').trigger('click')
    expect(vm.remove).toHaveBeenCalledWith('pqn_mine')

    await wrapper.find('[data-testid="question-edit"]').trigger('click')
    await wrapper.find('[data-testid="question-edit-content"]').setValue('세탁기 건조도 되나요?')
    await wrapper.find('[data-testid="question-edit-form"]').trigger('submit')
    expect(vm.saveEdit).toHaveBeenCalledWith('pqn_mine', '세탁기 건조도 되나요?')
  })

  it('0건이면 "첫 질문을 남겨 보세요"', async () => {
    const wrapper = await mountSection(vmWith({}))
    expect(wrapper.find('[data-testid="product-questions-empty"]').text()).toContain('첫 질문을 남겨 보세요')
  })
})
