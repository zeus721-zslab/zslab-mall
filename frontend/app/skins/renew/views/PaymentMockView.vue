<script setup lang="ts">
import type { PaymentMockPageVm } from '~/skins/contracts/payment-mock'

// renew 모의 결제(FE-71). 결제 금액 카드 형태만 바꾸고 제목·문구·버튼·동작·testid는 classic과 같다(order-resume-payment가 제목을 확인).
defineProps<{ vm: PaymentMockPageVm }>()

const BUTTON = 'flex h-14 w-full items-center justify-center rounded-full text-base font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40'
const LINK = 'mt-4 inline-flex min-h-11 items-center rounded-full bg-surface-muted px-6 text-sm font-bold text-ink transition duration-200 hover:bg-(--pastel-lavender-bg)'
</script>

<template>
  <div class="px-5 pb-8 pt-6 md:pt-10">
    <div class="mx-auto max-w-[480px]">
      <h1 class="text-3xl font-bold tracking-tight text-ink" data-testid="payment-mock-title">모의 결제</h1>
      <p class="mb-8 mt-2 text-sm text-sub">개발용 모의 PG입니다. 실제 결제가 이뤄지지 않습니다.</p>

      <!-- 결제 정보 없음(attemptKey 부재) -->
      <div v-if="!vm.hasAttemptKey" class="rounded-[28px] bg-white p-8 text-center">
        <p class="text-sm font-bold text-ink">결제 정보가 없습니다.</p>
        <NuxtLink to="/cart" :class="LINK">장바구니로 이동</NuxtLink>
      </div>

      <!-- 결제 종결(실패/취소) -->
      <div v-else-if="vm.resultMessage" class="rounded-[28px] bg-white p-8 text-center">
        <p class="text-base font-bold text-ink">{{ vm.resultMessage }}</p>
        <p class="mt-1 text-sm text-sub">장바구니 상품은 그대로 보관되어 있습니다.</p>
        <NuxtLink to="/cart" :class="LINK">장바구니로 돌아가기</NuxtLink>
      </div>

      <!-- 결제 진행: 결제 금액 카드 -->
      <div v-else class="rounded-[28px] bg-white p-6 md:p-8">
        <dl class="space-y-4">
          <div class="flex items-center justify-between text-sm">
            <dt class="text-sub">결제수단</dt>
            <dd class="rounded-full bg-surface-muted px-3 py-1 font-bold text-ink">{{ vm.methodLabel }}</dd>
          </div>
          <div class="flex items-baseline justify-between border-t border-line pt-4">
            <dt class="text-sm font-bold text-ink">결제금액</dt>
            <dd class="font-mono text-2xl font-semibold text-ink">{{ vm.amountLabel }}</dd>
          </div>
        </dl>

        <p v-if="vm.errorMessage" role="alert" class="mt-5 text-sm font-bold text-destructive" data-testid="payment-mock-error">{{ vm.errorMessage }}</p>

        <div class="mt-8 space-y-2">
          <button
            type="button"
            :class="[BUTTON, 'bg-primary text-primary-foreground hover:bg-primary-hover disabled:hover:bg-primary']"
            :disabled="vm.submitting"
            data-testid="payment-mock-success"
            @click="vm.pay('SUCCESS')"
          >
            {{ vm.submitting ? '처리 중…' : '결제 성공' }}
          </button>
          <button
            type="button"
            :class="[BUTTON, 'border border-line bg-white text-ink hover:border-ink']"
            :disabled="vm.submitting"
            @click="vm.pay('FAILURE')"
          >
            결제 실패
          </button>
          <button
            type="button"
            :class="[BUTTON, 'text-sub hover:bg-surface-muted hover:text-ink']"
            :disabled="vm.submitting"
            data-testid="payment-mock-cancel"
            @click="vm.pay('CANCEL')"
          >
            결제 취소
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
