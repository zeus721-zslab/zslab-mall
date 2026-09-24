<script setup lang="ts">
import type { WithdrawPageVm } from '~/skins/contracts/withdraw'

defineProps<{ vm: WithdrawPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-sm px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">회원 탈퇴</h1>

      <div class="rounded-card border border-line p-5">
        <p class="text-sm text-ink" style="white-space: pre-line" data-testid="withdraw-notice">{{ vm.notice }}</p>

        <label class="mt-4 flex items-center gap-2 text-sm text-ink">
          <input v-model="vm.agreed" type="checkbox" class="h-4 w-4 rounded border-line" />
          안내 사항을 확인했으며 탈퇴에 동의합니다
        </label>

        <p v-if="vm.errorMessage" role="alert" class="mt-4 text-sm text-soldout">{{ vm.errorMessage }}</p>

        <Button
          type="button"
          size="lg"
          variant="destructive"
          class="mt-4 w-full"
          :disabled="!vm.agreed || vm.submitting"
          @click="vm.handleWithdraw"
        >
          {{ vm.submitting ? '처리 중…' : '탈퇴하기' }}
        </Button>
      </div>
    </div>
  </div>
</template>
