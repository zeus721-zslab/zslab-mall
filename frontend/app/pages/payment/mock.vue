<script setup lang="ts">
import type { PaymentMethod } from '~/types/checkout'
import { PAYMENT_METHODS } from '~/lib/constants/payment'

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

async function pay(callbackType: 'SUCCESS' | 'FAILURE' | 'CANCEL', failureCode?: string): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''

  try {
    await checkout.sendPaymentCallback({ attemptKey, callbackType, failureCode })
    if (callbackType === 'SUCCESS') {
      await navigateTo(`/checkout/complete?orderPublicId=${encodeURIComponent(orderPublicId)}`)
      return
    }
    resultMessage.value = callbackType === 'FAILURE' ? '결제에 실패했습니다.' : '결제를 취소했습니다.'
  } catch {
    // webhook 4xx/5xx: 재시도 가능하도록 submitting 해제 후 안내.
    errorMessage.value = '결제 처리 중 문제가 발생했습니다. 다시 시도해 주세요.'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '모의 결제 · zslab-mall' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[480px] px-4 md:px-6">
      <h1 class="mb-1 text-2xl font-bold tracking-tight text-ink">모의 결제</h1>
      <p class="mb-6 text-sm text-sub">개발용 모의 PG입니다. 실제 결제가 이뤄지지 않습니다.</p>

      <!-- 결제 정보 없음(attemptKey 부재) -->
      <div v-if="!hasAttemptKey" class="rounded-card border border-line p-6 text-center">
        <p class="text-sm text-soldout">결제 정보가 없습니다.</p>
        <NuxtLink to="/cart" class="mt-2 inline-block text-sm text-primary underline">장바구니로 이동</NuxtLink>
      </div>

      <!-- 결제 종결(실패/취소) -->
      <div v-else-if="resultMessage" class="rounded-card border border-line p-6 text-center">
        <p class="text-sm font-medium text-ink">{{ resultMessage }}</p>
        <p class="mt-1 text-sm text-sub">장바구니 상품은 그대로 보관되어 있습니다.</p>
        <NuxtLink to="/cart" class="mt-3 inline-block text-sm text-primary underline">장바구니로 돌아가기</NuxtLink>
      </div>

      <!-- 결제 진행 -->
      <div v-else class="rounded-card border border-line p-6">
        <dl class="space-y-2 text-sm">
          <div class="flex items-center justify-between">
            <dt class="text-sub">결제수단</dt>
            <dd class="font-medium text-ink">{{ methodLabel }}</dd>
          </div>
          <div class="flex items-center justify-between">
            <dt class="text-sub">결제금액</dt>
            <dd class="text-lg font-bold text-ink">{{ amountLabel }}</dd>
          </div>
        </dl>

        <div v-if="errorMessage" class="mt-4">
          <p role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>
        </div>

        <div class="mt-6 space-y-2">
          <Button size="lg" class="w-full" :disabled="submitting" @click="pay('SUCCESS')">
            {{ submitting ? '처리 중…' : '결제 성공' }}
          </Button>
          <Button
            size="lg"
            variant="outline"
            class="w-full"
            :disabled="submitting"
            @click="pay('FAILURE', 'USER_TEST_FAIL')"
          >
            결제 실패
          </Button>
          <Button size="lg" variant="ghost" class="w-full" :disabled="submitting" @click="pay('CANCEL')">
            결제 취소
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>
