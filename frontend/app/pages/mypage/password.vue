<script setup lang="ts">
import { PASSWORD_MIN, PASSWORD_MAX } from '~/lib/constants/account'
import type { ChangePasswordRequest } from '~/types/user'
import {
  LOGIN_NOTICE_PASSWORD_CHANGED,
  LOGIN_NOTICE_QUERY,
  PASSWORD_CHANGE_REASON_QUERY,
  PASSWORD_CHANGE_REASON_TEMPORARY,
} from '~/lib/constants/auth'
import type { PasswordPageVm } from '~/skins/contracts/password'

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

const vm: PasswordPageVm = reactive({
  temporaryNotice,
  currentPassword,
  newPassword,
  newPasswordConfirm,
  submitting,
  errorMessage,
  handleSubmit,
  PASSWORD_MIN,
  PASSWORD_MAX,
})
</script>

<template>
  <component :is="useSkinView('PasswordView')" :vm="vm" />
</template>
