<script setup lang="ts">
import type { PaymentMethod } from '~/types/checkout'
import { PAYMENT_METHODS } from '~/lib/constants/payment'
import type { PaymentMockPageVm } from '~/skins/contracts/payment-mock'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const checkout = useCheckout()

// 체크아웃 성공 후 redirectUrl 쿼리를 그대로 승계받는다(attemptKey·amount·method·orderPublicId).
const attemptKey = String(route.query.attemptKey ?? '')
const amount = String(route.query.amount ?? '')
const method = String(route.query.method ?? '')
const orderPublicId = String(route.query.orderPublicId ?? '')

const hasAttemptKey = computed<boolean>(() => attemptKey !== '')

const methodLabel = computed<string>(() => {
  const found = PAYMENT_METHODS.find((option) => option.value === (method as PaymentMethod))
  return found ? found.label : method
})

const amountLabel = computed<string>(() => {
  const parsed = Number(amount)
  return amount !== '' && Number.isFinite(parsed) ? `${parsed.toLocaleString('ko-KR')}원` : amount
})

const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')
// 실패/취소 종결 안내(성공은 완료 화면으로 이동해 표시 안 함).
const resultMessage = ref<string>('')

async function pay(callbackType: 'SUCCESS' | 'FAILURE' | 'CANCEL'): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''

  try {
    await checkout.sendPaymentCallback({ attemptKey, callbackType })
    if (callbackType === 'SUCCESS') {
      await navigateTo(`/checkout/complete?orderPublicId=${encodeURIComponent(orderPublicId)}`)
      return
    }
    resultMessage.value = callbackType === 'FAILURE' ? '결제에 실패했습니다.' : '결제를 취소했습니다.'
  } catch {
    // mock 콜백 4xx/5xx: 재시도 가능하도록 submitting 해제 후 안내.
    errorMessage.value = '결제 처리 중 문제가 발생했습니다. 다시 시도해 주세요.'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '모의 결제 · zslab-mall' })

const vm: PaymentMockPageVm = reactive({
  hasAttemptKey,
  resultMessage,
  methodLabel,
  amountLabel,
  errorMessage,
  submitting,
  pay,
})
</script>

<template>
  <component :is="useSkinView('PaymentMockView')" :vm="vm" />
</template>
