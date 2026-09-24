<script setup lang="ts">
import type { SignupPageVm } from '~/skins/contracts/signup'
import RenewAuthFrame from '../components/RenewAuthFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 회원가입(FE-74). 가입·에러 문구(이메일 중복만 구분)는 classic과 같은 페이지 vm을 쓴다.
// 비밀번호 확인 칸은 renew만 있다 — 불일치 판정은 페이지(signupPasswordConfirm 선언 시)가 하고, 문구는 danger로 보인다.
defineProps<{ vm: SignupPageVm }>()

const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
</script>

<template>
  <RenewAuthFrame
    title="회원가입"
    eyebrow="Join"
    headline="zslab-mall과 함께해요"
    description="가입하면 장바구니·주문·배송지를 한곳에서 관리할 수 있어요."
  >
    <form class="space-y-5" @submit.prevent="vm.handleSubmit">
      <div>
        <label for="email" :class="LABEL">이메일</label>
        <input
          id="email"
          v-model="vm.email"
          data-testid="signup-email"
          type="email"
          autocomplete="email"
          required
          :maxlength="vm.EMAIL_MAX"
          :class="INPUT"
          placeholder="이메일을 입력하세요"
        />
      </div>

      <div class="grid gap-5 sm:grid-cols-2">
        <div>
          <label for="name" :class="LABEL">이름</label>
          <input
            id="name"
            v-model="vm.name"
            data-testid="signup-name"
            type="text"
            autocomplete="name"
            required
            :maxlength="vm.NAME_MAX"
            :class="INPUT"
            placeholder="이름을 입력하세요"
          />
        </div>
        <div>
          <label for="phone" :class="LABEL">휴대폰</label>
          <input
            id="phone"
            v-model="vm.phone"
            data-testid="signup-phone"
            type="tel"
            autocomplete="tel"
            required
            :maxlength="vm.PHONE_MAX"
            :class="INPUT"
            placeholder="휴대폰 번호를 입력하세요"
          />
        </div>
      </div>

      <div class="grid gap-5 sm:grid-cols-2">
        <div>
          <label for="password" :class="LABEL">비밀번호</label>
          <input
            id="password"
            v-model="vm.password"
            data-testid="signup-password"
            type="password"
            autocomplete="new-password"
            required
            :minlength="vm.PASSWORD_MIN"
            :maxlength="vm.PASSWORD_MAX"
            :class="INPUT"
            :placeholder="`${vm.PASSWORD_MIN}자 이상 입력하세요`"
          />
        </div>
        <div>
          <label for="passwordConfirm" :class="LABEL">비밀번호 확인</label>
          <input
            id="passwordConfirm"
            v-model="vm.passwordConfirm"
            data-testid="signup-password-confirm"
            type="password"
            autocomplete="new-password"
            required
            :minlength="vm.PASSWORD_MIN"
            :maxlength="vm.PASSWORD_MAX"
            :class="INPUT"
            placeholder="비밀번호를 다시 입력하세요"
          />
        </div>
      </div>

      <!-- 에러: 확인 불일치 · 이메일 중복 · 그 외 단일 문구 -->
      <RenewNotice v-if="vm.errorMessage" tone="danger" data-testid="signup-error">{{ vm.errorMessage }}</RenewNotice>

      <button type="submit" class="btn btn-primary btn-lg w-full" :disabled="vm.submitting" data-testid="signup-submit">
        <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
        {{ vm.submitting ? '가입 중…' : '회원가입' }}
      </button>
    </form>

    <p class="mt-8 text-center text-small text-sub">
      이미 계정이 있으신가요?
      <NuxtLink :to="vm.loginLink" class="btn btn-tertiary btn-sm text-primary max-md:min-h-11" data-testid="signup-login-link">로그인</NuxtLink>
    </p>
  </RenewAuthFrame>
</template>
