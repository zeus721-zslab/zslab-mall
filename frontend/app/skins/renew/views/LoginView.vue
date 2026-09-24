<script setup lang="ts">
import type { LoginPageVm } from '~/skins/contracts/login'
import RenewAuthFrame from '../components/RenewAuthFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 로그인(FE-74). 폼 동작·에러 문구·데모 로그인(FE-43)은 classic과 같은 페이지 vm을 쓴다(testid·안내 문구 동일).
defineProps<{ vm: LoginPageVm }>()

const LABEL = 'mb-1.5 block text-sm font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-[14px] border border-line bg-white px-4 text-sm text-ink transition duration-200 placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
const BUTTON = 'flex h-14 w-full items-center justify-center gap-2 rounded-full text-base font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40'
</script>

<template>
  <RenewAuthFrame
    title="로그인"
    eyebrow="Welcome back"
    headline="다시 만나서 반가워요"
    description="로그인하고 장바구니와 주문 내역을 이어서 확인하세요."
  >
    <RenewNotice v-if="vm.passwordChangedNotice" tone="success" class="mb-6" data-testid="login-password-changed-notice">
      비밀번호가 변경되었습니다. 다시 로그인해 주세요.
    </RenewNotice>

    <form class="space-y-5" @submit.prevent="vm.handleSubmit">
      <div>
        <label for="email" :class="LABEL">이메일</label>
        <input
          id="email"
          v-model="vm.email"
          data-testid="login-email"
          type="email"
          autocomplete="email"
          required
          :class="INPUT"
          placeholder="이메일을 입력하세요"
        />
      </div>

      <div>
        <label for="password" :class="LABEL">비밀번호</label>
        <input
          id="password"
          v-model="vm.password"
          data-testid="login-password"
          type="password"
          autocomplete="current-password"
          required
          :class="INPUT"
          placeholder="비밀번호를 입력하세요"
        />
      </div>

      <!-- 에러: 사유 무관 단일 문구(계정 열거·자격 노출 방지) -->
      <RenewNotice v-if="vm.errorMessage" tone="danger">{{ vm.errorMessage }}</RenewNotice>

      <button
        type="submit"
        :class="[BUTTON, 'bg-primary text-primary-foreground hover:bg-primary-hover disabled:hover:bg-primary']"
        :disabled="vm.submitting"
        data-testid="login-submit"
      >
        <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
        {{ vm.submitting ? '로그인 중…' : '로그인' }}
      </button>
    </form>

    <button
      v-if="vm.demoEnabled"
      type="button"
      :class="[BUTTON, 'mt-3 border border-line bg-white text-ink hover:bg-surface-muted']"
      data-testid="demo-login"
      :disabled="vm.submitting"
      @click="vm.handleDemoLogin"
    >
      데모 계정으로 둘러보기
    </button>

    <p class="mt-8 text-center text-sm text-sub">
      아직 회원이 아니신가요?
      <NuxtLink :to="vm.signupLink" class="ml-1 font-bold text-primary hover:underline" data-testid="login-signup-link">회원가입</NuxtLink>
    </p>
  </RenewAuthFrame>
</template>
