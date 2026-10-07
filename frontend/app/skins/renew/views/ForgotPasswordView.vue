<script setup lang="ts">
import type { ForgotPasswordPageVm } from '~/skins/contracts/forgot-password'
import { PASSWORD_RESET_MESSAGES } from '~/lib/constants/password-reset'
import RenewAuthFrame from '../components/RenewAuthFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 비밀번호 재설정 요청(D-269). 로그인과 같은 인증 틀. 요청 뒤에는 가입 여부와 무관한 같은 안내만 보인다.
defineProps<{ vm: ForgotPasswordPageVm }>()

const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <RenewAuthFrame
    title="비밀번호 찾기"
    eyebrow="zslab-mall"
    headline="비밀번호를 잊으셨나요?"
    description="가입한 이메일로 비밀번호 재설정 링크를 보내 드려요."
  >
    <div v-if="vm.unavailable" class="space-y-6" data-testid="forgot-password-unavailable">
      <RenewNotice tone="info">{{ PASSWORD_RESET_MESSAGES.unavailable }}</RenewNotice>
      <NuxtLink to="/login" class="btn btn-secondary btn-lg w-full">로그인으로 돌아가기</NuxtLink>
    </div>

    <div v-else-if="vm.requested" class="space-y-6" data-testid="forgot-password-requested">
      <RenewNotice tone="success">{{ PASSWORD_RESET_MESSAGES.requested }}</RenewNotice>
      <NuxtLink to="/login" class="btn btn-secondary btn-lg w-full" data-testid="forgot-password-login-link">로그인으로 돌아가기</NuxtLink>
    </div>

    <form v-else class="space-y-5" @submit.prevent="vm.handleSubmit">
      <div>
        <label for="email" :class="LABEL">이메일</label>
        <input
          id="email"
          v-model="vm.email"
          data-testid="forgot-password-email"
          type="email"
          autocomplete="email"
          required
          :maxlength="vm.EMAIL_MAX"
          :class="INPUT"
          placeholder="가입한 이메일을 입력하세요"
        />
      </div>

      <RenewNotice v-if="vm.errorMessage" tone="danger" data-testid="forgot-password-error">{{ vm.errorMessage }}</RenewNotice>

      <button type="submit" class="btn btn-primary btn-lg w-full" :disabled="vm.submitting" data-testid="forgot-password-submit">
        <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
        {{ vm.submitting ? '보내는 중…' : '재설정 링크 받기' }}
      </button>

      <p class="text-center text-small text-sub">
        <NuxtLink to="/login" class="btn btn-tertiary btn-sm text-primary max-md:min-h-11">로그인으로 돌아가기</NuxtLink>
      </p>
    </form>
  </RenewAuthFrame>
</template>
