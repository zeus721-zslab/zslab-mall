<script setup lang="ts">
import { EMAIL_MAX, NAME_MAX, PHONE_MAX, PASSWORD_MIN, PASSWORD_MAX } from '~/lib/constants/account'
import type { SignupPageVm } from '~/skins/contracts/signup'

// 공개 페이지(POST /users permitAll)라 definePageMeta 미부착. role은 auth.signup 내부에서 BUYER 고정.
const auth = useAuthStore()
const route = useRoute()

// 비밀번호 확인 칸은 선언한 스킨(renew)만 보이고 검사한다(FE-74) — classic은 확인 칸도 검사도 없다. 확인 값은 BE로 보내지 않는다.
const passwordConfirmRequired = useSkinNeeds('signupPasswordConfirm')

const email = ref<string>('')
const name = ref<string>('')
const phone = ref<string>('')
const password = ref<string>('')
const passwordConfirm = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

// 이미 인증된 사용자가 /signup에 오면 홈으로 돌려보낸다(가입 폼 노출 불필요·login.vue 가드 패턴 복제).
if (auth.isAuthenticated) {
  await navigateTo('/')
}

async function handleSubmit(): Promise<void> {
  if (submitting.value) return
  // 확인 일치는 서버 왕복 전 클라에서 즉시 검사한다(비밀번호 변경 화면과 같은 방식).
  if (passwordConfirmRequired && password.value !== passwordConfirm.value) {
    errorMessage.value = '비밀번호가 일치하지 않습니다'
    return
  }
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

// 로그인 링크(FE-74)는 로그인 화면에서 받아 온 redirect를 그대로 돌려준다(가입 성공 후 이동은 기존대로 /mypage).
const loginLink = computed<string>(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect !== '' ? `/login?redirect=${encodeURIComponent(redirect)}` : '/login'
})

useSeoMeta({
  title: '회원가입 · zslab-mall',
  description: 'zslab-mall 회원가입',
})

const vm: SignupPageVm = reactive({
  email,
  name,
  phone,
  password,
  submitting,
  errorMessage,
  handleSubmit,
  EMAIL_MAX,
  NAME_MAX,
  PHONE_MAX,
  PASSWORD_MIN,
  PASSWORD_MAX,
  passwordConfirm,
  loginLink,
})
</script>

<template>
  <component :is="useSkinView('SignupView')" :vm="vm" />
</template>
