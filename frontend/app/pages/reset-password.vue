<script setup lang="ts">
import { PASSWORD_MAX, PASSWORD_MAX_BYTES_MESSAGE, PASSWORD_MIN, exceedsPasswordMaxBytes } from '~/lib/constants/account'
import { LOGIN_NOTICE_QUERY } from '~/lib/constants/auth'
import {
  FORGOT_PASSWORD_PAGE,
  LOGIN_NOTICE_PASSWORD_RESET,
  PASSWORD_RESET_MESSAGES,
  RESET_PASSWORD_PAGE,
  RESET_PASSWORD_TOKEN_QUERY,
  isPasswordResetTokenInvalid,
} from '~/lib/constants/password-reset'
import type { ResetPasswordPageVm } from '~/skins/contracts/reset-password'

// 비밀번호 재설정(D-269). 메일 링크(?token=)로 들어와 새 비밀번호를 정한다. 공개 페이지라 definePageMeta 미부착.
// 토큰은 확정 요청 본문으로만 보내고 콘솔 로그에 남기지 않는다. 링크 토큰이 다른 사이트로 Referer에 실려 나가지 않게 referrer를 끈다.
// (첫 요청 URL의 쿼리는 gateway·SSR 접근 로그에 남을 수 있다 — 1회용·30분이라 위험이 한정되며 로그 마스킹은 gateway 작업 범위·D-269 §8.)
useHead({ meta: [{ name: 'referrer', content: 'no-referrer' }] })

const route = useRoute()
const { confirmReset } = usePasswordReset()

const rawToken = route.query[RESET_PASSWORD_TOKEN_QUERY]
const token = typeof rawToken === 'string' ? rawToken : ''
// 토큰을 읽은 뒤 주소창·방문 기록에서 쿼리를 지운다(라우터 상태는 그대로 두고 URL만 바꾼다 — 화면 재생성 없음).
onMounted(() => {
  if (token !== '') window.history.replaceState(window.history.state, '', RESET_PASSWORD_PAGE)
})

const newPassword = ref<string>('')
const newPasswordConfirm = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')
// 쿼리 토큰이 없으면 처음부터 링크 무효 안내. BE가 만료·사용됨으로 거절해도 같은 상태로 바뀐다(사유는 구분하지 않는다).
const linkInvalid = ref<boolean>(token === '')
const linkInvalidMessage = ref<string>(token === '' ? PASSWORD_RESET_MESSAGES.tokenMissing : PASSWORD_RESET_MESSAGES.tokenInvalid)

async function handleSubmit(): Promise<void> {
  if (submitting.value || linkInvalid.value) return
  errorMessage.value = ''
  if (newPassword.value !== newPasswordConfirm.value) {
    errorMessage.value = PASSWORD_RESET_MESSAGES.confirmMismatch
    return
  }
  if (exceedsPasswordMaxBytes(newPassword.value)) {
    errorMessage.value = PASSWORD_MAX_BYTES_MESSAGE
    return
  }
  submitting.value = true
  try {
    await confirmReset(token, newPassword.value)
    await navigateTo(`/login?${LOGIN_NOTICE_QUERY}=${LOGIN_NOTICE_PASSWORD_RESET}`)
  } catch (error) {
    if (isPasswordResetTokenInvalid(error)) {
      linkInvalidMessage.value = PASSWORD_RESET_MESSAGES.tokenInvalid
      linkInvalid.value = true
      return
    }
    console.warn('[password-reset] confirm failed')
    errorMessage.value = PASSWORD_RESET_MESSAGES.confirmFailed
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '비밀번호 재설정 · zslab-mall', description: 'zslab-mall 비밀번호 재설정' })

const vm: ResetPasswordPageVm = reactive({
  newPassword,
  newPasswordConfirm,
  submitting,
  linkInvalid,
  linkInvalidMessage,
  errorMessage,
  handleSubmit,
  forgotPasswordLink: FORGOT_PASSWORD_PAGE,
  PASSWORD_MIN,
  PASSWORD_MAX,
})
</script>

<template>
  <component :is="useSkinView('ResetPasswordView')" :vm="vm" />
</template>
