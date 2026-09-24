<script setup lang="ts">
import type { WithdrawPageVm } from '~/skins/contracts/withdraw'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'

// renew 회원 탈퇴(FE-72). 안내 문구(위험 조작 규약)·동의 체크·탈퇴 동작은 classic과 같은 페이지 vm을 쓴다.
defineProps<{ vm: WithdrawPageVm }>()
</script>

<template>
  <MypageFrame title="회원 탈퇴">
    <section class="max-w-[720px] rounded-[28px] bg-white p-6 md:p-8" aria-label="회원 탈퇴 안내">
      <RenewNotice tone="warning">
        <p class="leading-relaxed" style="white-space: pre-line" data-testid="withdraw-notice">{{ vm.notice }}</p>
      </RenewNotice>

      <label class="mt-6 flex w-fit cursor-pointer items-center gap-3 text-sm text-ink">
        <RenewCheckbox :checked="vm.agreed" @change="(checked) => (vm.agreed = checked)" />
        안내 사항을 확인했으며 탈퇴에 동의합니다
      </label>

      <RenewNotice v-if="vm.errorMessage" tone="danger" class="mt-5">{{ vm.errorMessage }}</RenewNotice>

      <button
        type="button"
        class="mt-6 flex h-14 w-full items-center justify-center gap-2 rounded-full bg-destructive text-base font-bold text-destructive-foreground transition duration-200 hover:opacity-90 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-destructive focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40 disabled:hover:opacity-40 sm:w-48"
        :disabled="!vm.agreed || vm.submitting"
        @click="vm.handleWithdraw"
      >
        <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
        {{ vm.submitting ? '처리 중…' : '탈퇴하기' }}
      </button>
    </section>
  </MypageFrame>
</template>
