<script setup lang="ts">
import {
  SELLER_DEMO_STATUS_PATH,
  SELLER_HOME_PATH,
  SELLER_LOGIN_NOTICE_PASSWORD_CHANGED,
  SELLER_LOGIN_NOTICE_QUERY,
} from '#layers/seller/app/lib/constants/auth'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

// 셀러 로그인(Track 90-A·관리자 admin/login.vue 동형·seller_token 세션). 공개 페이지라 seller 미들웨어 미부착·seller-vuetify 미들웨어만(미인증 상태에서 Vuetify 로드).
definePageMeta({ layout: 'seller-auth', middleware: ['seller-vuetify'] })

const sellerAuth = useSellerAuthStore()
const route = useRoute()

// 비밀번호 변경 완료 후 재로그인 안내(FE-50·settings/password.vue가 query로 전달·구매자 login.vue 동형).
const passwordChangedNotice = computed<boolean>(() => route.query[SELLER_LOGIN_NOTICE_QUERY] === SELLER_LOGIN_NOTICE_PASSWORD_CHANGED)

const email = ref<string>('')
const password = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

/** 로그인 후 복귀 경로. redirect query가 /seller 하위 내부 경로일 때만 허용(오픈 리다이렉트·타 영역 이탈 방지). */
function resolveRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith(`${SELLER_HOME_PATH}/`)) {
    return redirect
  }
  return SELLER_HOME_PATH
}

// 이미 셀러 세션이 있으면 폼 노출 없이 복귀 경로로 보낸다(임시 비밀번호 세션이면 seller 미들웨어가 변경 경로로 다시 돌린다). 사용자·관리자 세션 유무는 무관.
if (sellerAuth.isAuthenticated) {
  await navigateTo(resolveRedirect())
}

/**
 * 로그인 시도 공통 흐름(폼·데모). submitting을 공유해 이중클릭·중복 요청을 막고, 성공 시 복귀 경로로 이동한다.
 * BE는 사유(미존재·비번·role 부적격·PENDING/TERMINATED 셀러 상태 차단 D-190)를 401로 통합·은닉하고, 응답 role≠SELLER 거절도 같은 문구로 안내한다.
 */
async function attemptLogin(action: () => Promise<void>): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await action()
    await navigateTo(resolveRedirect())
  } catch {
    errorMessage.value = '이메일 또는 비밀번호를 확인하세요'
  } finally {
    submitting.value = false
  }
}

function handleSubmit(): Promise<void> {
  return attemptLogin(() => sellerAuth.login(email.value, password.value))
}

// 데모 버튼은 서버 라우트가 env 계정을 갖고 있을 때만 노출한다(값은 받지 않고 boolean만). 조회 실패는 미노출로 처리한다.
const demoEnabled = ref<boolean>(false)
onMounted(async () => {
  try {
    const status = await $fetch<{ enabled: boolean }>(SELLER_DEMO_STATUS_PATH)
    demoEnabled.value = status.enabled
  } catch (error) {
    console.warn('[seller-demo] status check failed', error)
  }
})

function handleDemoLogin(): Promise<void> {
  return attemptLogin(() => sellerAuth.loginDemo())
}

useSeoMeta({ title: '셀러 로그인 · zslab-mall', robots: 'noindex, nofollow' })
</script>

<template>
  <v-row justify="center" align="start" class="fill-height pt-16">
    <v-col cols="12" sm="8" md="5" lg="4" xl="3">
      <div class="slr-page-header text-center mb-6">
        <div class="text-h6 font-weight-bold">zslab-mall</div>
        <div class="slr-page-header__description text-body-2">셀러 센터</div>
      </div>
      <v-card class="pa-6">
        <v-alert v-if="passwordChangedNotice" type="success" variant="tonal" density="compact" class="mb-4" role="status" data-testid="seller-login-password-changed-notice">
          비밀번호가 변경되었습니다. 새 비밀번호로 다시 로그인해 주세요.
        </v-alert>
        <p class="text-body-2 text-medium-emphasis mb-5">셀러 구성원 계정으로 로그인하세요.</p>
        <v-form @submit.prevent="handleSubmit">
          <v-text-field id="seller-email" v-model="email" label="이메일" type="email" autocomplete="username" required />
          <v-text-field id="seller-password" v-model="password" label="비밀번호" type="password" autocomplete="current-password" required />
          <v-alert v-if="errorMessage" type="error" class="mb-4" role="alert" data-testid="seller-login-error">{{ errorMessage }}</v-alert>
          <v-btn type="submit" color="primary" size="large" block :loading="submitting" :disabled="submitting">로그인</v-btn>
        </v-form>
        <template v-if="demoEnabled">
          <v-divider class="my-5" />
          <v-btn data-testid="seller-demo-login" variant="outlined" size="large" block :disabled="submitting" @click="handleDemoLogin">셀러 데모 로그인</v-btn>
        </template>
      </v-card>
    </v-col>
  </v-row>
</template>
