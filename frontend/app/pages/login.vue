<script setup lang="ts">
import { BUYER_ROLE, DEMO_STATUS_PATH, LOGIN_NOTICE_PASSWORD_CHANGED, LOGIN_NOTICE_QUERY } from '~/lib/constants/auth'
import type { LoginPageVm } from '~/skins/contracts/login'

// 공개 페이지(permitAll 로그인 엔드포인트 소비)라 definePageMeta 미부착. buyer 몰이므로 role은 BUYER 고정(UI 노출 없음).
const auth = useAuthStore()
const route = useRoute()

// 비밀번호 변경 완료 후 재로그인 안내(Track 84·mypage/password.vue가 query로 전달).
const passwordChangedNotice = computed<boolean>(() => route.query[LOGIN_NOTICE_QUERY] === LOGIN_NOTICE_PASSWORD_CHANGED)

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

// 데모 버튼(FE-43)은 서버 라우트가 env 계정을 갖고 있을 때만 노출한다(값은 받지 않고 boolean만). 조회 실패는 미노출로 처리한다.
const demoEnabled = ref<boolean>(false)
onMounted(async () => {
  try {
    const status = await $fetch<{ enabled: boolean }>(DEMO_STATUS_PATH)
    demoEnabled.value = status.enabled
  } catch (error) {
    console.warn('[demo] status check failed', error)
  }
})

/**
 * 데모 로그인(FE-43). 서버 라우트가 비공개 env 계정(저권한 BUYER)으로 BE 로그인을 대행해 포트폴리오 방문자가
 * 1클릭으로 둘러보게 한다. 브라우저는 자격증명을 모른다. submitting을 handleSubmit과 공유해 이중클릭·중복 요청을 막는다.
 */
async function handleDemoLogin(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.loginDemo()
    await navigateTo(resolveRedirect())
  } catch {
    // 로그인 실패 사유 은닉 원칙(handleSubmit과 동일 단일 문구).
    errorMessage.value = '이메일 또는 비밀번호를 확인하세요'
  } finally {
    submitting.value = false
  }
}

// 회원가입 링크(FE-74)는 받은 redirect를 그대로 넘긴다 — 가입 화면의 로그인 링크가 다시 돌려주고, 허용 여부는 로그인 시점에 resolveRedirect가 판정한다.
const signupLink = computed<string>(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect !== '' ? `/signup?redirect=${encodeURIComponent(redirect)}` : '/signup'
})

useSeoMeta({
  title: '로그인 · zslab-mall',
  description: 'zslab-mall 로그인',
})

const vm: LoginPageVm = reactive({
  passwordChangedNotice,
  email,
  password,
  submitting,
  errorMessage,
  demoEnabled,
  handleSubmit,
  handleDemoLogin,
  signupLink,
})
</script>

<template>
  <component :is="useSkinView('LoginView')" :vm="vm" />
</template>
