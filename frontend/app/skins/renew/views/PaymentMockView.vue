<script setup lang="ts">
import type { PaymentMockPageVm } from '~/skins/contracts/payment-mock'
import RenewNotice from '../components/RenewNotice.vue'

// renew 모의 결제(FE-71 · FE-78). 결제 수단·금액 흰 카드. 제목·문구·버튼·동작·testid는 classic과 같다(order-resume-payment가 제목을 확인).
defineProps<{ vm: PaymentMockPageVm }>()

const CARD = 'rounded-card bg-white shadow-e1'
</script>

<template>
  <div class="px-5 pb-8 pt-6 md:pt-10">
    <div class="mx-auto max-w-[480px]">
      <h1 class="text-h1 text-ink" data-testid="payment-mock-title">모의 결제</h1>
      <RenewNotice tone="info" class="mb-8 mt-4">개발용 모의 PG입니다. 실제 결제가 이뤄지지 않습니다.</RenewNotice>

      <!-- 결제 정보 없음(attemptKey 부재) -->
      <div v-if="!vm.hasAttemptKey" :class="[CARD, 'px-5 py-8 text-center md:px-6']">
        <p class="text-body font-bold text-ink">결제 정보가 없습니다.</p>
        <NuxtLink to="/cart" class="btn btn-secondary btn-md mt-4">장바구니로 이동</NuxtLink>
      </div>

      <!-- 결제 종결(실패/취소) -->
      <div v-else-if="vm.resultMessage" :class="[CARD, 'px-5 py-8 text-center md:px-6']">
        <p class="text-h3 text-ink">{{ vm.resultMessage }}</p>
        <p class="mt-1 text-body text-sub">장바구니 상품은 그대로 보관되어 있습니다.</p>
        <NuxtLink to="/cart" class="btn btn-secondary btn-md mt-4">장바구니로 돌아가기</NuxtLink>
      </div>

      <!-- 결제 진행: 결제 수단·금액 카드 -->
      <div v-else :class="[CARD, 'p-5 md:p-6']">
        <dl class="space-y-4">
          <div class="flex items-center justify-between text-body">
            <dt class="text-sub">결제수단</dt>
            <dd class="font-bold text-ink">{{ vm.methodLabel }}</dd>
          </div>
          <div class="flex items-baseline justify-between border-t border-line pt-4">
            <dt class="text-body font-bold text-ink">결제금액</dt>
            <dd class="text-h2 font-semibold tabular-nums text-ink">{{ vm.amountLabel }}</dd>
          </div>
        </dl>

        <RenewNotice v-if="vm.errorMessage" tone="danger" class="mt-5" data-testid="payment-mock-error">{{ vm.errorMessage }}</RenewNotice>

        <div class="mt-8 space-y-2">
          <button type="button" class="btn btn-primary btn-lg w-full" :disabled="vm.submitting" data-testid="payment-mock-success" @click="vm.pay('SUCCESS')">
            {{ vm.submitting ? '처리 중…' : '결제 성공' }}
          </button>
          <button type="button" class="btn btn-secondary btn-lg w-full" :disabled="vm.submitting" @click="vm.pay('FAILURE')">결제 실패</button>
          <button type="button" class="btn btn-tertiary btn-lg w-full" :disabled="vm.submitting" data-testid="payment-mock-cancel" @click="vm.pay('CANCEL')">
            결제 취소
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
