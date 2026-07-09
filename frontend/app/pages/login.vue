<script setup lang="ts">
import { BUYER_ROLE } from '~/lib/constants/auth'

// 공개 페이지(permitAll 로그인 엔드포인트 소비)라 definePageMeta 미부착. buyer 몰이므로 role은 BUYER 고정(UI 노출 없음).
const auth = useAuthStore()
const route = useRoute()
const config = useRuntimeConfig()

const email = ref<string>('')
const password = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

/**
 * 로그인 후 복귀 경로. redirect query가 내부 절대경로일 때만 허용한다.
 * 외부 URL·protocol-relative('//evil.com')는 오픈 리다이렉트 방지로 무시하고 홈으로 보낸다.
 */
function resolveRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    return redirect
  }
  return '/'
}

// 이미 인증된 사용자가 /login에 오면 복귀 경로(또는 홈)로 돌려보낸다(로그인 폼 노출 불필요).
if (auth.isAuthenticated) {
  await navigateTo(resolveRedirect())
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login(email.value, password.value, BUYER_ROLE)
    await navigateTo(resolveRedirect())
  } catch {
    // BE는 사유(미존재·비번·role·검증)를 401/400으로 통합·은닉하므로 FE도 단일 문구로 안내한다(recon-76 §1-2).
    errorMessage.value = '이메일 또는 비밀번호를 확인하세요'
  } finally {
    submitting.value = false
  }
}

/**
 * 데모 로그인. runtimeConfig.public의 공개 데모 자격증명(저권한 BUYER)으로 로그인해 포트폴리오 방문자가
 * 1클릭으로 둘러보게 한다. submitting을 handleSubmit과 공유해 이중클릭·중복 요청을 막는다.
 * 값 미주입 시(런타임 env 누락) 빈 자격증명 login 호출을 막고 단일 안내만 표시한다.
 */
async function handleDemoLogin(): Promise<void> {
  if (submitting.value) return
  const demoEmail = config.public.demoEmail
  const demoPassword = config.public.demoPassword
  if (!demoEmail || !demoPassword) {
    errorMessage.value = '데모 계정이 설정되지 않았습니다'
    return
  }
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login(demoEmail, demoPassword, BUYER_ROLE)
    await navigateTo(resolveRedirect())
  } catch {
    // 로그인 실패 사유 은닉 원칙(handleSubmit과 동일 단일 문구).
    errorMessage.value = '이메일 또는 비밀번호를 확인하세요'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({
  title: '로그인 · zslab-mall',
  description: 'zslab-mall 로그인',
})
</script>

<template>
  <div class="flex min-h-[70vh] items-center justify-center px-4 py-12">
    <div class="w-full max-w-sm">
      <h1 class="mb-6 text-center text-2xl font-bold tracking-tight text-primary">로그인</h1>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div class="space-y-1.5">
          <label for="email" class="block text-sm font-medium text-ink">이메일</label>
          <input
            id="email"
            v-model="email"
            type="email"
            autocomplete="email"
            required
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="이메일을 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="password" class="block text-sm font-medium text-ink">비밀번호</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="비밀번호를 입력하세요"
          />
        </div>

        <!-- 에러: 사유 무관 단일 문구(계정 열거·자격 노출 방지) -->
        <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="submitting">
          {{ submitting ? '로그인 중…' : '로그인' }}
        </Button>
      </form>

      <Button
        type="button"
        variant="outline"
        size="lg"
        class="mt-3 w-full"
        :disabled="submitting"
        @click="handleDemoLogin"
      >
        데모 계정으로 둘러보기
      </Button>
    </div>
  </div>
</template>
