<script setup lang="ts">
import { PASSWORD_MIN, PASSWORD_MAX } from '~/lib/constants/account'
import type { ChangePasswordRequest } from '~/types/user'
import {
  LOGIN_NOTICE_PASSWORD_CHANGED,
  LOGIN_NOTICE_QUERY,
  PASSWORD_CHANGE_REASON_QUERY,
  PASSWORD_CHANGE_REASON_TEMPORARY,
} from '~/lib/constants/auth'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const { changePassword } = useProfile()
const auth = useAuthStore()
const cart = useCartStore()
const route = useRoute()

// 임시 비밀번호 로그인(강제 상태 쿠키 또는 미들웨어 리다이렉트 사유 query)이면 안내 문구를 띄운다(Track 84).
const temporaryNotice = computed<boolean>(
  () => auth.passwordChangeRequired || route.query[PASSWORD_CHANGE_REASON_QUERY] === PASSWORD_CHANGE_REASON_TEMPORARY,
)

const currentPassword = ref<string>('')
const newPassword = ref<string>('')
const newPasswordConfirm = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  errorMessage.value = ''
  // 새 비밀번호 확인 일치는 서버 왕복 전 클라에서 즉시 검증(BE는 확인 필드를 받지 않음).
  if (newPassword.value !== newPasswordConfirm.value) {
    errorMessage.value = '새 비밀번호가 일치하지 않습니다'
    return
  }
  submitting.value = true
  try {
    const request: ChangePasswordRequest = {
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
    }
    await changePassword(request)
    // 204 성공 시 BE가 기존 토큰을 무효화한다(D-178) → 강제 상태 해제·세션 정리(withdraw.vue와 같은 auth·cart 조합) 후 재로그인 유도.
    auth.clearPasswordChangeRequired()
    auth.logout()
    cart.clear()
    await navigateTo(`/login?${LOGIN_NOTICE_QUERY}=${LOGIN_NOTICE_PASSWORD_CHANGED}`)
    return
  } catch (submitError) {
    const statusCode = (submitError as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent('/mypage/password')}`)
      return
    }
    // 현재 비밀번호 불일치·정책 위반 모두 400(MALFORMED_REQUEST/VALIDATION_FAILED)로 통합·사유 은닉 → 단일 문구.
    errorMessage.value = '현재 비밀번호를 확인하세요'
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '비밀번호 변경 · zslab-mall', description: 'zslab-mall 비밀번호 변경' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-sm px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">비밀번호 변경</h1>

      <p v-if="temporaryNotice" role="status" class="mb-4 rounded-card border border-line bg-gray-50 p-3 text-sm text-ink" data-testid="password-temporary-notice">
        임시 비밀번호로 로그인했습니다. 새 비밀번호로 변경해 주세요.
      </p>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div class="space-y-1.5">
          <label for="currentPassword" class="block text-sm font-medium text-ink">현재 비밀번호</label>
          <input
            id="currentPassword"
            v-model="currentPassword"
            type="password"
            autocomplete="current-password"
            required
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="현재 비밀번호를 입력하세요"
          />
        </div>

        <div class="space-y-1.5">
          <label for="newPassword" class="block text-sm font-medium text-ink">새 비밀번호</label>
          <input
            id="newPassword"
            v-model="newPassword"
            type="password"
            autocomplete="new-password"
            required
            :minlength="PASSWORD_MIN"
            :maxlength="PASSWORD_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            :placeholder="`새 비밀번호를 입력하세요 (${PASSWORD_MIN}자 이상)`"
          />
        </div>

        <div class="space-y-1.5">
          <label for="newPasswordConfirm" class="block text-sm font-medium text-ink">새 비밀번호 확인</label>
          <input
            id="newPasswordConfirm"
            v-model="newPasswordConfirm"
            type="password"
            autocomplete="new-password"
            required
            :minlength="PASSWORD_MIN"
            :maxlength="PASSWORD_MAX"
            class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
            placeholder="새 비밀번호를 다시 입력하세요"
          />
        </div>

        <p v-if="errorMessage" role="alert" class="text-sm text-soldout">{{ errorMessage }}</p>

        <Button type="submit" size="lg" class="w-full" :disabled="submitting">
          {{ submitting ? '변경 중…' : '비밀번호 변경' }}
        </Button>
      </form>
    </div>
  </div>
</template>
