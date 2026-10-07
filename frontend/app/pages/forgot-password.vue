<script setup lang="ts">
import { EMAIL_MAX } from '~/lib/constants/account'
import { PASSWORD_RESET_MESSAGES } from '~/lib/constants/password-reset'
import type { ForgotPasswordPageVm } from '~/skins/contracts/forgot-password'

// 비밀번호 재설정 요청(D-269). 공개 페이지(permitAll)라 definePageMeta 미부착. 가입 여부와 무관하게 같은 안내를 보이고(이메일 열거 방지),
// 실제 발송 여부는 BE가 판단한다. 로그인된 사용자는 마이페이지 비밀번호 변경을 쓰므로 홈으로 돌려보낸다(login.vue 가드 패턴).
const auth = useAuthStore()
if (auth.isAuthenticated) {
  await navigateTo('/')
}

const { fetchAvailability, requestReset } = usePasswordReset()

// 메일 발송이 꺼져 있으면(BE availability false) 주소로 직접 들어와도 폼 대신 준비 중 안내를 보인다 — 오지 않을 메일을 기다리게 하지 않는다.
// 조회 실패는 폼을 유지한다(BE가 비활성이면 요청을 무시할 뿐 응답은 같다).
const unavailable = ref<boolean>(false)
onMounted(async () => {
  try {
    unavailable.value = !(await fetchAvailability())
  } catch (error) {
    console.warn('[password-reset] availability check failed', error)
  }
})

const email = ref<string>('')
const submitting = ref<boolean>(false)
const requested = ref<boolean>(false)
const errorMessage = ref<string>('')

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await requestReset(email.value)
    requested.value = true
  } catch (error) {
    // BE는 대상과 무관하게 202를 주므로 여기 오는 것은 형식 오류(400)·네트워크·서버 오류뿐이다.
    console.warn('[password-reset] request failed', error)
    errorMessage.value = PASSWORD_RESET_MESSAGES.requestFailed
  } finally {
    submitting.value = false
  }
}

useSeoMeta({ title: '비밀번호 찾기 · zslab-mall', description: 'zslab-mall 비밀번호 재설정 요청' })

const vm: ForgotPasswordPageVm = reactive({
  unavailable,
  email,
  submitting,
  requested,
  errorMessage,
  handleSubmit,
  EMAIL_MAX,
})
</script>

<template>
  <component :is="useSkinView('ForgotPasswordView')" :vm="vm" />
</template>
