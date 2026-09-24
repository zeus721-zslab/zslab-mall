<script setup lang="ts">
import type { WithdrawPageVm } from '~/skins/contracts/withdraw'
import { MYPAGE_HOME_PATH } from '~/lib/constants/mypage-menu'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'

// renew 회원 탈퇴(FE-72). 안내 문구(위험 조작 규약)·동의 체크·탈퇴 동작은 classic과 같은 페이지 vm을 쓴다.
// FE-79: 탈퇴 = 위험 버튼 · 취소 = 보조 버튼(마이페이지 홈으로 돌아가기).
defineProps<{ vm: WithdrawPageVm }>()
</script>

<template>
  <MypageFrame title="회원 탈퇴">
    <section class="max-w-[720px] rounded-card bg-white p-5 shadow-e1 md:p-6" aria-label="회원 탈퇴 안내">
      <RenewNotice tone="warning">
        <p class="leading-relaxed" style="white-space: pre-line" data-testid="withdraw-notice">{{ vm.notice }}</p>
      </RenewNotice>

      <label class="mt-6 flex min-h-11 w-fit cursor-pointer items-center gap-3 text-body text-ink">
        <RenewCheckbox :checked="vm.agreed" @change="(checked) => (vm.agreed = checked)" />
        안내 사항을 확인했으며 탈퇴에 동의합니다
      </label>

      <RenewNotice v-if="vm.errorMessage" tone="danger" class="mt-5">{{ vm.errorMessage }}</RenewNotice>

      <div class="mt-6 flex flex-col gap-3 sm:flex-row">
        <NuxtLink :to="MYPAGE_HOME_PATH" class="btn btn-secondary btn-lg w-full sm:w-48">취소</NuxtLink>
        <button type="button" class="btn btn-danger btn-lg w-full sm:w-48" :disabled="!vm.agreed || vm.submitting" @click="vm.handleWithdraw">
          <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-current border-t-transparent motion-reduce:animate-none" aria-hidden="true"></span>
          {{ vm.submitting ? '처리 중…' : '탈퇴하기' }}
        </button>
      </div>
    </section>
  </MypageFrame>
</template>
