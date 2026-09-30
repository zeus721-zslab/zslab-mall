<script setup lang="ts">
import {
  INQUIRY_LIST_PATH,
  INQUIRY_OPEN_QUERY,
  type InquiryCategory,
  isOrderPublicId,
} from '~/lib/constants/inquiry'
import { inquiryCreateErrorMessage, inquiryErrorStatus, validateInquiryContent } from '~/lib/utils/inquiry-error'
import { buildInquiryOrderOptions } from '~/lib/utils/inquiry-order-options'
import type { InquiryNewPageVm } from '~/skins/contracts/inquiry-new'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login?redirect=(이 URL · ?order 포함)로 보내고 로그인 후 돌아온다.
definePageMeta({ middleware: 'buyer' })

// 운영자 문의 작성(Track 106-4). 입구 = 채팅 도우미 · 내 문의 "새 문의". 주문 상세에서 연 도우미는 ?order=ord_…를 붙여 그 주문을 미리 고른다.
const route = useRoute()
const queryOrder = route.query.order
const attachedOrderId = isOrderPublicId(queryOrder) ? queryOrder : null

const { data: orderPage, pending: orderOptionsPending, error: orderOptionsError } = useInquiryOrderOptions()
const actions = useInquiryActions()

const category = ref<InquiryCategory | null>(null)
const content = ref('')
const orderId = ref<string>(attachedOrderId ?? '')
const submitting = ref(false)
const errorText = ref<string | null>(null)

const orderOptions = computed(() => buildInquiryOrderOptions(orderPage.value?.items ?? [], attachedOrderId))
// 취소·반품·교환은 주문 상세의 품목 버튼에서 바로 신청하는 편이 빠르다 — 첨부 주문이 있으면 그 상세, 없으면 주문 내역으로 안내한다.
const claimGuidePath = computed(() => (orderId.value ? `/orders/${orderId.value}` : '/orders'))

async function submit(): Promise<void> {
  if (submitting.value) return
  if (category.value === null) {
    errorText.value = '문의 카테고리를 골라 주세요.'
    return
  }
  const invalid = validateInquiryContent(content.value)
  if (invalid) {
    errorText.value = invalid
    return
  }
  submitting.value = true
  errorText.value = null
  try {
    const created = await actions.create(category.value, content.value, orderId.value || null)
    await navigateTo({ path: INQUIRY_LIST_PATH, query: { [INQUIRY_OPEN_QUERY]: created.inquiryId } })
  } catch (createError) {
    if (inquiryErrorStatus(createError) === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
      return
    }
    errorText.value = inquiryCreateErrorMessage(createError)
  } finally {
    submitting.value = false
  }
}

const vm: InquiryNewPageVm = reactive({
  category,
  content,
  orderId,
  orderOptions,
  orderOptionsPending,
  orderOptionsFailed: computed(() => orderOptionsError.value !== undefined && orderOptionsError.value !== null),
  showClaimGuide: computed(() => category.value === 'CLAIM'),
  claimGuidePath,
  submitting,
  errorText,
  submit,
})
</script>

<template>
  <component :is="useSkinView('InquiryNewView')" :vm="vm" />
</template>
