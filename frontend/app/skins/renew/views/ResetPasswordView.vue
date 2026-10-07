<script setup lang="ts">
import type { ResetPasswordPageVm } from '~/skins/contracts/reset-password'
import RenewAuthFrame from '../components/RenewAuthFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 비밀번호 재설정(D-269). 링크가 무효(없음·만료·사용됨)면 폼 대신 재요청 안내, 아니면 새 비밀번호·확인 폼.
defineProps<{ vm: ResetPasswordPageVm }>()

const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <RenewAuthFrame
    title="비밀번호 재설정"
    eyebrow="zslab-mall"
    headline="새 비밀번호를 정해 주세요"
    description="재설정이 끝나면 기존 로그인은 모두 해제돼요."
  >
    <div v-if="vm.linkInvalid" class="space-y-6" data-testid="reset-password-link-invalid">
      <RenewNotice tone="danger">{{ vm.linkInvalidMessage }}</RenewNotice>
      <NuxtLink :to="vm.forgotPasswordLink" class="btn btn-primary btn-lg w-full" data-testid="reset-password-request-again">
        재설정 다시 요청하기
      </NuxtLink>
    </div>

    <form v-else class="space-y-5" @submit.prevent="vm.handleSubmit">
      <div>
        <label for="newPassword" :class="LABEL">새 비밀번호</label>
        <input
          id="newPassword"
          v-model="vm.newPassword"
          data-testid="reset-password-new"
          type="password"
          autocomplete="new-password"
          required
          :minlength="vm.PASSWORD_MIN"
          :maxlength="vm.PASSWORD_MAX"
          :class="INPUT"
          :placeholder="`새 비밀번호를 입력하세요 (${vm.PASSWORD_MIN}자 이상)`"
        />
      </div>
      <div>
        <label for="newPasswordConfirm" :class="LABEL">새 비밀번호 확인</label>
        <input
          id="newPasswordConfirm"
          v-model="vm.newPasswordConfirm"
          data-testid="reset-password-new-confirm"
          type="password"
          autocomplete="new-password"
          required
          :minlength="vm.PASSWORD_MIN"
          :maxlength="vm.PASSWORD_MAX"
          :class="INPUT"
          placeholder="새 비밀번호를 다시 입력하세요"
        />
      </div>

      <RenewNotice v-if="vm.errorMessage" tone="danger" data-testid="reset-password-error">{{ vm.errorMessage }}</RenewNotice>

      <button type="submit" class="btn btn-primary btn-lg w-full" :disabled="vm.submitting" data-testid="reset-password-submit">
        <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
        {{ vm.submitting ? '변경 중…' : '비밀번호 재설정' }}
      </button>
    </form>
  </RenewAuthFrame>
</template>
