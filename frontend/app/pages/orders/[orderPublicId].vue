<script setup lang="ts">
import { AUTO_CONFIRM_GUIDE, ITEM_CONFIRM_WARNING, PAYMENT_EXPIRE_GUIDE, orderStatusLabel } from '~/lib/constants/order'
import { claimableTypes, claimTypeLabel, orderItemStatusLabel, type ClaimType } from '~/lib/constants/claim'
import { PAYMENT_METHODS, paymentMethodLabel } from '~/lib/constants/payment'
import { formatDateTime } from '~/lib/utils/datetime'
import { canResumePayment, isPaymentExpired, paymentResumeFailure, PAYMENT_EXPIRED_NOTICE } from '~/lib/utils/payment-resume'
import type { PaymentResumeErrorLike } from '~/lib/utils/payment-resume'
import { resolvePaymentRedirect } from '~/lib/payment-redirect'
import type { PaymentMethod } from '~/types/checkout'
import type { OrderItem } from '~/types/order'
import type { OrderDetailPageVm } from '~/skins/contracts/order-detail'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const orderPublicId = route.params.orderPublicId as string

const { data, pending, error, refresh } = useOrderDetail(orderPublicId)

// 401(세션 만료)은 /login 유도. 404(타인·미존재)는 존재 은닉이라 안내만(재조회 버튼 없이 목록으로 유도).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
    }
  },
  { immediate: true },
)

// 404와 그 외 오류 문구 구분(존재 은닉이라 미노출도 404).
const errorMessage = computed<string>(() =>
  (error.value as { statusCode?: number } | null)?.statusCode === 404
    ? '주문을 찾을 수 없습니다'
    : '주문을 불러오지 못했습니다',
)

function formatPrice(value: number): string {
  return `${value.toLocaleString('ko-KR')}원`
}

// 클레임 요청 폼으로 진입(name은 표시용·서버 미전송). 원주문 id는 폼이 query로 받지 않으므로 성공 후 /orders로 유도.
// 교환(FE-30-1 α)은 옵션 후보 조회·필터용으로 상품/변형 public id·주문 단가를 함께 넘긴다(규칙 보장은 BE).
function goClaim(item: OrderItem, type: ClaimType): void {
  const base = `/claims/new?orderItem=${item.orderItemId}&type=${type}&name=${encodeURIComponent(item.productName ?? '')}`
  const exchangeQuery = type === 'EXCHANGE'
    ? `&product=${item.productId ?? ''}&variant=${item.variantId ?? ''}&unitPrice=${item.unitPrice}`
    : ''
  navigateTo(base + exchangeQuery)
}

// 결제 재개(Track 102 FE-64): 결제대기 주문에서 BE 재결제(D-60)를 호출하고 체크아웃과 같은 방식으로 결제창에 진입한다.
// 결제수단 기본값은 체크아웃 폼과 같은 첫 선택지다. 실패는 인라인 안내만 한다(구매자 앱에 토스트 인프라가 없다).
const { retryPayment } = useCheckout()
const payMethod = ref<PaymentMethod>(PAYMENT_METHODS[0]!.value)
const paying = ref<boolean>(false)
const payError = ref<string>('')

async function submitResumePayment(): Promise<void> {
  if (paying.value) return
  paying.value = true
  payError.value = ''
  try {
    const result = await retryPayment(orderPublicId, payMethod.value)
    const payment = result.data.payment
    if (payment.publicId === null || !payment.redirectUrl) {
      payError.value = '결제 준비에 실패했습니다. 잠시 후 다시 시도해 주세요.'
      return
    }
    // 재결제 응답의 Location은 결제(payment)를 가리키므로 주문번호를 직접 넘긴다(payment-redirect 주석 참조).
    const redirect = resolvePaymentRedirect(payment.redirectUrl, result.location, orderPublicId)
    if (redirect.kind === 'external') {
      await navigateTo(redirect.url, { external: true })
      return
    }
    await navigateTo(redirect.path)
  } catch (payFetchError) {
    // 실패 8종의 문구·후속 동작 판정은 순수 함수가 갖는다(Track 102 보완·payment-resume.ts).
    const failure = paymentResumeFailure(payFetchError as PaymentResumeErrorLike)
    if (failure.login) {
      await navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
      return
    }
    payError.value = failure.message
    // 상태가 이미 바뀐 실패는 상세를 다시 읽어 결제 영역 자체가 사라지게 한다.
    if (failure.refresh) await refresh()
  } finally {
    paying.value = false
  }
}

// 구매확정(FE-53·C-06): DELIVERED 품목만. 확인 패널(인라인·구매자 앱은 다이얼로그·토스트 인프라 부재)에서 반품·교환 불가 경고 후 호출한다.
// 성공·실패 안내도 같은 품목 아래 인라인. 제출 중에는 버튼을 잠가 중복 호출을 막는다.
const { confirmPurchase } = useOrderActions()
const confirmTargetId = ref<string | null>(null)
const confirming = ref<boolean>(false)
const confirmNotice = ref<{ orderItemId: string; tone: 'success' | 'error'; text: string } | null>(null)

function openConfirm(item: OrderItem): void {
  confirmNotice.value = null
  confirmTargetId.value = item.orderItemId
}

async function submitConfirm(item: OrderItem): Promise<void> {
  if (confirming.value) return
  confirming.value = true
  try {
    await confirmPurchase(orderPublicId, item.orderItemId)
    confirmNotice.value = { orderItemId: item.orderItemId, tone: 'success', text: '구매확정이 완료되었습니다.' }
    confirmTargetId.value = null
    await refresh()
  } catch (submitError) {
    // 401은 로그인 유도, 그 외(422 상태 불일치·404 등)는 서버 detail 우선 표시 후 재조회로 최신 상태 반영(.catch(()=>{}) 금지).
    const failure = submitError as { statusCode?: number; data?: { detail?: string } }
    if (failure.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/orders/${orderPublicId}`)}`)
      return
    }
    confirmNotice.value = {
      orderItemId: item.orderItemId,
      tone: 'error',
      text: failure.data?.detail ?? '구매확정에 실패했습니다. 잠시 후 다시 시도하세요.',
    }
    confirmTargetId.value = null
    await refresh()
  } finally {
    confirming.value = false
  }
}

// 탭 제목은 사람이 읽는 주문번호만 쓴다(내부 id 노출 금지 · orderNo 없는 옛 응답은 "주문 상세").
useSeoMeta({
  title: () => (data.value?.orderNo ? `주문 ${data.value.orderNo} · zslab-mall` : '주문 상세 · zslab-mall'),
  description: 'zslab-mall 주문 상세',
})

const vm: OrderDetailPageVm = reactive({
  pending,
  error,
  data,
  refresh,
  errorMessage,
  formatPrice,
  goClaim,
  payMethod,
  paying,
  payError,
  submitResumePayment,
  confirmTargetId,
  confirming,
  confirmNotice,
  openConfirm,
  submitConfirm,
  AUTO_CONFIRM_GUIDE,
  ITEM_CONFIRM_WARNING,
  PAYMENT_EXPIRE_GUIDE,
  PAYMENT_EXPIRED_NOTICE,
  PAYMENT_METHODS,
  orderStatusLabel,
  orderItemStatusLabel,
  claimableTypes,
  claimTypeLabel,
  canResumePayment,
  isPaymentExpired,
  paymentMethodLabel,
  formatDateTime,
})
</script>

<template>
  <component :is="useSkinView('OrderDetailView')" :vm="vm" />
</template>
