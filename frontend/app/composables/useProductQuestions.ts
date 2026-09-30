import { BUYER_ROLE } from '~/lib/constants/auth'
import { PRODUCT_QUESTION_PAGE_SIZE } from '~/lib/constants/product-question'
import {
  isStaleQuestionError,
  questionChangeErrorMessage,
  questionCreateErrorMessage,
  questionErrorStatus,
  validateQuestionContent,
} from '~/lib/utils/product-question-error'
import type { PagedResponse } from '~/types/order'
import type { ProductQuestionItem } from '~/types/product-question'
import type { ProductQuestionNotice, ProductQuestionsVm } from '~/skins/contracts/product-questions'

/**
 * 상품 상세 "이 상품, 물어보세요" 섹션(Track 106-2). 페이지가 스킨이 productQuestions를 선언했을 때만 호출한다.
 * 입력(draft)이 바뀌면 즉시 답(useProductQuestionSuggest)을 갱신하고, 등록은 구매자만 한다 — 비로그인·비구매자는 로그인으로 보내고 돌아오면
 * 상세로 복귀한다(redirect 쿼리 · 입력 초안은 보존하지 않는다). 공개 목록 첫 페이지는 useAsyncData로 SSR에서 받고(구매자 래퍼라 쿠키가 실려
 * writtenByMe가 채워진다), 더보기로 받은 페이지는 로컬 상태로 이어 붙인다. 등록·수정·삭제 뒤에는 첫 페이지부터 다시 받는다.
 */
export function useProductQuestions(productPublicId: string): ProductQuestionsVm {
  const api = useBuyerApi()
  const route = useRoute()
  const auth = useAuthStore()
  const actions = useProductQuestionActions()
  const suggest = useProductQuestionSuggest(productPublicId)
  const listPath: string = `/v1/products/${productPublicId}/questions`

  const firstPage = useAsyncData(`product-questions:${productPublicId}`, () =>
    api<PagedResponse<ProductQuestionItem>>(listPath, { query: { page: 0, size: PRODUCT_QUESTION_PAGE_SIZE } }),
  )

  // 더보기로 이어 받은 항목 · 다음 페이지 번호 · 마지막 응답의 hasNext(null = 아직 더보기 전 → 첫 페이지 값).
  const moreItems = ref<ProductQuestionItem[]>([])
  const nextPage = ref(1)
  const moreHasNext = ref<boolean | null>(null)
  const loadingMore = ref(false)

  const items = computed<ProductQuestionItem[]>(() => [...(firstPage.data.value?.items ?? []), ...moreItems.value])
  const hasNext = computed(() => moreHasNext.value ?? firstPage.data.value?.hasNext ?? false)

  async function loadMore(): Promise<void> {
    if (loadingMore.value || !hasNext.value) return
    loadingMore.value = true
    try {
      const response = await api<PagedResponse<ProductQuestionItem>>(listPath, {
        query: { page: nextPage.value, size: PRODUCT_QUESTION_PAGE_SIZE },
      })
      moreItems.value = [...moreItems.value, ...response.items]
      nextPage.value += 1
      moreHasNext.value = response.hasNext
    } catch (loadError) {
      // 더보기 실패는 버튼을 그대로 두어 다시 누르게 한다 — 원인만 남긴다.
      console.warn('[product-question] 질문 더보기 실패', loadError)
    } finally {
      loadingMore.value = false
    }
  }

  /** 첫 페이지부터 다시 받는다(등록·수정·삭제 뒤 · 규칙 거절 뒤). */
  async function reload(): Promise<void> {
    moreItems.value = []
    nextPage.value = 1
    moreHasNext.value = null
    await firstPage.refresh()
  }

  const draft = ref('')
  const submitting = ref(false)
  const notice = ref<ProductQuestionNotice | null>(null)
  const actionPendingId = ref<string | null>(null)

  function setDraft(text: string): void {
    draft.value = text
    suggest.onInput(text)
  }

  async function goLogin(): Promise<void> {
    await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
  }

  async function submit(): Promise<void> {
    if (submitting.value) return
    if (!auth.isAuthenticated || auth.role !== BUYER_ROLE) {
      await goLogin()
      return
    }
    const invalid = validateQuestionContent(draft.value)
    if (invalid) {
      notice.value = { tone: 'warning', text: invalid }
      return
    }
    submitting.value = true
    notice.value = null
    try {
      await actions.create(productPublicId, draft.value.trim())
      draft.value = ''
      suggest.clear()
      notice.value = { tone: 'success', text: '질문을 남겼어요. 셀러가 답변하면 마이페이지 "내 질문"에서 확인할 수 있어요.' }
      await reload()
    } catch (createError) {
      if (questionErrorStatus(createError) === 401) {
        await goLogin()
        return
      }
      notice.value = { tone: 'danger', text: questionCreateErrorMessage(createError) }
    } finally {
      submitting.value = false
    }
  }

  /** 수정·삭제 공통: 401 → 로그인 · 422/404 → 안내 후 목록 다시 읽기 · 그 밖 → 안내만. */
  async function handleChangeError(changeError: unknown): Promise<void> {
    if (questionErrorStatus(changeError) === 401) {
      await goLogin()
      return
    }
    notice.value = { tone: 'warning', text: questionChangeErrorMessage(changeError) }
    if (isStaleQuestionError(changeError)) await reload()
  }

  async function saveEdit(questionId: string, content: string): Promise<boolean> {
    if (actionPendingId.value !== null) return false
    const invalid = validateQuestionContent(content)
    if (invalid) {
      notice.value = { tone: 'warning', text: invalid }
      return false
    }
    actionPendingId.value = questionId
    notice.value = null
    try {
      await actions.update(questionId, content.trim())
      notice.value = { tone: 'success', text: '질문을 수정했어요.' }
      await reload()
      return true
    } catch (updateError) {
      await handleChangeError(updateError)
      return false
    } finally {
      actionPendingId.value = null
    }
  }

  async function remove(questionId: string): Promise<void> {
    if (actionPendingId.value !== null) return
    actionPendingId.value = questionId
    notice.value = null
    try {
      await actions.remove(questionId)
      notice.value = { tone: 'success', text: '질문을 삭제했어요.' }
      await reload()
    } catch (removeError) {
      await handleChangeError(removeError)
    } finally {
      actionPendingId.value = null
    }
  }

  return reactive({
    draft,
    setDraft,
    suggestions: suggest.suggestions,
    submitting,
    submit,
    notice,
    items,
    totalCount: computed(() => firstPage.data.value?.totalCount ?? 0),
    pending: computed(() => firstPage.status.value === 'pending' && !firstPage.data.value),
    failed: computed(() => firstPage.status.value === 'error'),
    retry: reload,
    hasNext,
    loadingMore,
    loadMore,
    actionPendingId,
    saveEdit,
    remove,
  })
}
