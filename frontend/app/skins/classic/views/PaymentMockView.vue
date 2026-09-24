<script setup lang="ts">
import type { PaymentMockPageVm } from '~/skins/contracts/payment-mock'

defineProps<{ vm: PaymentMockPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[480px] px-4 md:px-6">
      <h1 class="mb-1 text-2xl font-bold tracking-tight text-ink" data-testid="payment-mock-title">모의 결제</h1>
      <p class="mb-6 text-sm text-sub">개발용 모의 PG입니다. 실제 결제가 이뤄지지 않습니다.</p>

      <!-- 결제 정보 없음(attemptKey 부재) -->
      <div v-if="!vm.hasAttemptKey" class="rounded-card border border-line p-6 text-center">
        <p class="text-sm text-soldout">결제 정보가 없습니다.</p>
        <NuxtLink to="/cart" class="mt-2 inline-block text-sm text-primary underline">장바구니로 이동</NuxtLink>
      </div>

      <!-- 결제 종결(실패/취소) -->
      <div v-else-if="vm.resultMessage" class="rounded-card border border-line p-6 text-center">
        <p class="text-sm font-medium text-ink">{{ vm.resultMessage }}</p>
        <p class="mt-1 text-sm text-sub">장바구니 상품은 그대로 보관되어 있습니다.</p>
        <NuxtLink to="/cart" class="mt-3 inline-block text-sm text-primary underline">장바구니로 돌아가기</NuxtLink>
      </div>

      <!-- 결제 진행 -->
      <div v-else class="rounded-card border border-line p-6">
        <dl class="space-y-2 text-sm">
          <div class="flex items-center justify-between">
            <dt class="text-sub">결제수단</dt>
            <dd class="font-medium text-ink">{{ vm.methodLabel }}</dd>
          </div>
          <div class="flex items-center justify-between">
            <dt class="text-sub">결제금액</dt>
            <dd class="text-lg font-bold text-ink">{{ vm.amountLabel }}</dd>
          </div>
        </dl>

        <div v-if="vm.errorMessage" class="mt-4">
          <p role="alert" class="text-sm text-soldout" data-testid="payment-mock-error">{{ vm.errorMessage }}</p>
        </div>

        <div class="mt-6 space-y-2">
          <Button size="lg" class="w-full" :disabled="vm.submitting" data-testid="payment-mock-success" @click="vm.pay('SUCCESS')">
            {{ vm.submitting ? '처리 중…' : '결제 성공' }}
          </Button>
          <Button
            size="lg"
            variant="outline"
            class="w-full"
            :disabled="vm.submitting"
            @click="vm.pay('FAILURE')"
          >
            결제 실패
          </Button>
          <Button size="lg" variant="ghost" class="w-full" :disabled="vm.submitting" data-testid="payment-mock-cancel" @click="vm.pay('CANCEL')">
            결제 취소
          </Button>
        </div>
      </div>
    </div>
  </div>
</template>
