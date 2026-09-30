<script setup lang="ts">
import { type InquiryCategory, INQUIRY_NEW_PATH, INQUIRY_OPEN_QUERY, INQUIRY_PAGE_SIZE } from '~/lib/constants/inquiry'
import {
  inquiryChangeErrorMessage,
  inquiryErrorStatus,
  isStaleInquiryError,
  validateInquiryContent,
} from '~/lib/utils/inquiry-error'
import { shouldCheckAnswer, withCheckedInquiries } from '~/lib/utils/inquiry-unread'
import type { InquiryNotice, MypageInquiriesPageVm } from '~/skins/contracts/mypage-inquiries'
import type { MyInquiry } from '~/types/inquiry'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

// 내 문의(Track 106-4 · 내 질문 페이지 선례). 답변이 달린 미확인 문의를 펼치면 확인 API를 부르고, 성공하면 이 화면의 표시만 바로 지운다.
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

const openQuery = route.query[INQUIRY_OPEN_QUERY]
const initialOpenId = typeof openQuery === 'string' ? openQuery : null

const { data, pending, error, refresh } = useMyInquiries(page, INQUIRY_PAGE_SIZE)
const actions = useInquiryActions()

// 세션 만료 등으로 서버가 401이면 로그인으로 유도(미들웨어는 진입 UX만·실인가 SoT는 서버).
watch(
  error,
  (fetchError) => {
    if (inquiryErrorStatus(fetchError) === 401) navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
  },
  { immediate: true },
)

const notice = ref<InquiryNotice | null>(null)
const actionPendingId = ref<string | null>(null)
// 이 화면에서 확인을 마친 문의(재조회 전까지 미확인 표시를 지운다) · 확인 요청 중인 문의(같은 문의 중복 호출 방지).
const checkedIds = ref<string[]>([])
const checkingIds = new Set<string>()

const items = computed<MyInquiry[]>(() => withCheckedInquiries(data.value?.items ?? [], checkedIds.value))

function checkAnswer(item: MyInquiry): void {
  if (!shouldCheckAnswer(item, checkingIds.has(item.inquiryId))) return
  checkingIds.add(item.inquiryId)
  actions
    .checkAnswer(item.inquiryId)
    .then(() => {
      checkedIds.value = [...checkedIds.value, item.inquiryId]
    })
    .catch((checkError: unknown) => {
      // 확인 실패는 화면 흐름을 막지 않는다 — 미확인 표시를 그대로 두고 다음 펼침에 다시 부른다.
      console.warn('[inquiry] 답변 확인 처리 실패 — 다음 펼침에 재시도', checkError)
    })
    .finally(() => {
      checkingIds.delete(item.inquiryId)
    })
}

async function handleChangeError(changeError: unknown): Promise<void> {
  if (inquiryErrorStatus(changeError) === 401) {
    await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }
  notice.value = { tone: 'warning', text: inquiryChangeErrorMessage(changeError) }
  if (isStaleInquiryError(changeError)) await refresh()
}

async function saveEdit(inquiryId: string, category: InquiryCategory, content: string): Promise<boolean> {
  if (actionPendingId.value !== null) return false
  const invalid = validateInquiryContent(content)
  if (invalid) {
    notice.value = { tone: 'warning', text: invalid }
    return false
  }
  actionPendingId.value = inquiryId
  notice.value = null
  try {
    await actions.update(inquiryId, category, content)
    notice.value = { tone: 'success', text: '문의를 수정했어요.' }
    await refresh()
    return true
  } catch (updateError) {
    await handleChangeError(updateError)
    return false
  } finally {
    actionPendingId.value = null
  }
}

async function remove(inquiryId: string): Promise<void> {
  if (actionPendingId.value !== null) return
  actionPendingId.value = inquiryId
  notice.value = null
  try {
    await actions.remove(inquiryId)
    notice.value = { tone: 'success', text: '문의를 삭제했어요.' }
    await refresh()
  } catch (removeError) {
    await handleChangeError(removeError)
  } finally {
    actionPendingId.value = null
  }
}

const vm: MypageInquiriesPageVm = reactive({
  items,
  page,
  hasNext: computed(() => data.value?.hasNext ?? false),
  movePage,
  pending,
  error,
  retry: () => void refresh(),
  notice,
  initialOpenId,
  newInquiryPath: INQUIRY_NEW_PATH,
  actionPendingId,
  checkAnswer,
  saveEdit,
  remove,
})
</script>

<template>
  <component :is="useSkinView('MypageInquiriesView')" :vm="vm" />
</template>
