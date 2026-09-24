<script setup lang="ts">
import type { LoginPageVm } from '~/skins/contracts/login'
import RenewAuthFrame from '../components/RenewAuthFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 로그인(FE-74). 폼 동작·에러 문구·데모 로그인(FE-43)은 classic과 같은 페이지 vm을 쓴다(testid·안내 문구 동일).
defineProps<{ vm: LoginPageVm }>()

const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'

// 비밀번호 찾기는 준비 중 기준(FE-76 · 인증 영역·검토 등급 A)에 해당해 안내만 띄운다(FE-81). 페이지 이동·요청 없음.
const forgotPasswordNoticeShown = ref<boolean>(false)
</script>

<template>
  <RenewAuthFrame
    title="로그인"
    eyebrow="zslab-mall"
    headline="다시 만나서 반가워요"
    description="로그인하고 장바구니와 주문 내역을 이어서 확인하세요."
    :note="vm.demoEnabled ? '데모 계정으로 바로 둘러볼 수 있어요' : undefined"
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
        <!-- 3차 버튼의 좌우 여백만큼 당겨 글자 끝을 입력칸 오른쪽 끝에 맞춘다. -->
        <div class="mt-1 flex justify-end">
          <button
            type="button"
            class="btn btn-tertiary btn-sm -mr-4 text-sub max-md:min-h-11"
            data-testid="login-forgot-password"
            @click="forgotPasswordNoticeShown = true"
          >
            비밀번호를 잊으셨나요?
          </button>
        </div>
      </div>

      <RenewNotice v-if="forgotPasswordNoticeShown" tone="info" data-testid="login-forgot-password-notice">
        비밀번호 찾기는 준비 중입니다.
      </RenewNotice>

      <!-- 에러: 사유 무관 단일 문구(계정 열거·자격 노출 방지) -->
      <RenewNotice v-if="vm.errorMessage" tone="danger">{{ vm.errorMessage }}</RenewNotice>

      <button type="submit" class="btn btn-primary btn-lg w-full" :disabled="vm.submitting" data-testid="login-submit">
        <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
        {{ vm.submitting ? '로그인 중…' : '로그인' }}
      </button>
    </form>

    <button
      v-if="vm.demoEnabled"
      type="button"
      class="btn btn-secondary btn-lg mt-3 w-full"
      data-testid="demo-login"
      :disabled="vm.submitting"
      @click="vm.handleDemoLogin"
    >
      데모 계정으로 둘러보기
    </button>

    <p class="mt-8 text-center text-small text-sub">
      아직 회원이 아니신가요?
      <NuxtLink :to="vm.signupLink" class="btn btn-tertiary btn-sm text-primary max-md:min-h-11" data-testid="login-signup-link">회원가입</NuxtLink>
    </p>
  </RenewAuthFrame>
</template>
