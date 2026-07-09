<script setup lang="ts">
import { EMAIL_MAX, NAME_MAX, PHONE_MAX, PASSWORD_MIN, PASSWORD_MAX } from '~/lib/constants/account'

// 공개 페이지(POST /users permitAll)라 definePageMeta 미부착. role은 auth.signup 내부에서 BUYER 고정.
const auth = useAuthStore()

const email = ref<string>('')
const name = ref<string>('')
const phone = ref<string>('')
const password = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

// 이미 인증된 사용자가 /signup에 오면 홈으로 돌려보낸다(가입 폼 노출 불필요·login.vue 가드 패턴 복제).
if (auth.isAuthenticated) {
  await navigateTo('/')
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.signup(email.value, name.value, phone.value, password.value)
    // 가입 + 자동 로그인 성공 → 마이페이지로(/mypage는 FE-13 STEP 3에서 신설).
    await navigateTo('/mypage')
  } catch (signupError) {
    // 가입은 성공했으나 자동 로그인만 실패 → 계정은 이미 존재하므로 로그인 페이지로 유도.
    if ((signupError as { signupSucceeded?: boolean }).signupSucceeded) {
      await navigateTo('/login')
      return
    }
    // 이메일 중복만 사유 구분(409). 그 외(검증 400 등)는 계정 열거 방지 겸 단일 문구.
    const statusCode = (signupError as { statusCode?: number }).statusCode
    const code = (signupError as { data?: { code?: string } }).data?.code
    if (statusCode === 409 && code === 'EMAIL_ALREADY_EXISTS') {
      errorMessage.value = '이미 사용 중인 이메일입니다'
      return
    }
    errorMessage.value = '가입에 실패했습니다. 입력을 확인하세요'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({
  title: '회원가입 · zslab-mall',
  description: 'zslab-mall 회원가입',
})
</script>

<template>
  <div class="flex min-h-[70vh] items-center justify-center px-4 py-12">
    <div class="w-full max-w-sm">
      <h1 class="mb-6 text-center text-2xl font-bold tracking-tight text-primary">회원가입</h1>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div class="space-y-1.5">
          <label for="email" class="block text-sm font-medium text-ink">이메일</label>
          <input
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            required
            :maxlength="EMAIL_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이메일을 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="name" class="block text-sm font-medium text-ink">이름</label>
          <input
            id="name"
            v-model="name"
            type="text"
            autocomplete="name"
            required
            :maxlength="NAME_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이름을 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="phone" class="block text-sm font-medium text-ink">휴대폰</label>
          <input
            id="phone"
            v-model="phone"
            type="tel"
            autocomplete="tel"
            required
            :maxlength="PHONE_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="휴대폰 번호를 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="password" class="block text-sm font-medium text-ink">비밀번호</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="new-password"
            required
            :minlength="PASSWORD_MIN"
            :maxlength="PASSWORD_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            :placeholder="`비밀번호를 입력하세요 (${PASSWORD_MIN}자 이상)`"
          />
        </div>

        <!-- 에러: 이메일 중복만 구분·그 외 단일 문구 -->
        <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="submitting">
          {{ submitting ? '가입 중…' : '회원가입' }}
        </Button>
      </form>

      <p class="mt-6 text-center text-sm text-gray-500">
        이미 계정이 있으신가요?
        <NuxtLink to="/login" class="font-medium text-primary hover:underline">로그인</NuxtLink>
      </p>
    </div>
  </div>
</template>
