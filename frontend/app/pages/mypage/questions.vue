<script setup lang="ts">
import { PRODUCT_QUESTION_PAGE_SIZE } from '~/lib/constants/product-question'
import {
  isStaleQuestionError,
  questionChangeErrorMessage,
  questionErrorStatus,
  validateQuestionContent,
} from '~/lib/utils/product-question-error'
import type { ProductQuestionNotice } from '~/skins/contracts/product-questions'
import type { MypageQuestionsPageVm } from '~/skins/contracts/mypage-questions'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const router = useRouter()

// URL(?page=)이 단일 소스다(주문 내역 FE-63 선례). 숫자가 아니면 첫 페이지.
const page = computed<number>(() => {
  const raw = route.query.page
  return typeof raw === 'string' && /^\d+$/.test(raw) ? Number(raw) : 0
})
function movePage(nextPage: number): void {
  void router.replace({ query: { ...route.query, page: String(Math.max(nextPage, 0)) } })
}

const { data, pending, error, refresh } = useMyProductQuestions(page, PRODUCT_QUESTION_PAGE_SIZE)
const actions = useProductQuestionActions()

// 세션 만료 등으로 서버가 401이면 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버).
watch(
  error,
  (fetchError) => {
    if (questionErrorStatus(fetchError) === 401) navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
  },
  { immediate: true },
)

const notice = ref<ProductQuestionNotice | null>(null)
const actionPendingId = ref<string | null>(null)

async function handleChangeError(changeError: unknown): Promise<void> {
  if (questionErrorStatus(changeError) === 401) {
    await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }
  notice.value = { tone: 'warning', text: questionChangeErrorMessage(changeError) }
  if (isStaleQuestionError(changeError)) await refresh()
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
    await refresh()
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
    await refresh()
  } catch (removeError) {
    await handleChangeError(removeError)
  } finally {
    actionPendingId.value = null
  }
}

const vm: MypageQuestionsPageVm = reactive({
  items: computed(() => data.value?.items ?? []),
  page,
  hasNext: computed(() => data.value?.hasNext ?? false),
  movePage,
  pending,
  error,
  retry: () => void refresh(),
  notice,
  actionPendingId,
  saveEdit,
  remove,
})
</script>

<template>
  <component :is="useSkinView('MypageQuestionsView')" :vm="vm" />
</template>
