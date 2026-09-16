<script setup lang="ts">
import { ADMIN_DEMO_STATUS_PATH, ADMIN_HOME_PATH } from '#layers/admin/app/lib/constants/auth'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'

// 관리자 로그인(FE-22·D-1·FE-22c Vuetify 폼·FE-22d admin_token 세션). 공개 페이지라 admin 미들웨어 미부착·vuetify 미들웨어만(미인증 상태에서 Vuetify 로드 — 로그인 화면도 관리자 셸 일부).
definePageMeta({ layout: 'admin-auth', middleware: ['vuetify'] })

const adminAuth = useAdminAuthStore()
const route = useRoute()

const email = ref<string>('')
const password = ref<string>('')
const submitting = ref<boolean>(false)
const errorMessage = ref<string>('')

/** 로그인 후 복귀 경로. redirect query가 /admin 하위 내부 경로일 때만 허용(오픈 리다이렉트·사용자 영역 이탈 방지). */
function resolveRedirect(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith(`${ADMIN_HOME_PATH}/`)) {
    return redirect
  }
  return ADMIN_HOME_PATH
}

// 이미 관리자 세션이 있으면 폼 노출 없이 복귀 경로로 보낸다. 사용자 세션(auth_token) 유무는 무관(세션 독립·FE-22d).
if (adminAuth.isAuthenticated) {
  await navigateTo(resolveRedirect())
}

/**
 * 로그인 시도 공통 흐름(폼·데모). submitting을 공유해 이중클릭·중복 요청을 막고, 성공 시 복귀 경로로 이동한다.
 * BE는 사유(미존재·비번·role 부적격)를 401로 통합·은닉하고, 응답 role≠ADMIN 거절도 같은 문구로 안내한다(login.vue와 동일 원칙).
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
  return attemptLogin(() => adminAuth.login(email.value, password.value))
}

// 데모 버튼(FE-23)은 서버 라우트가 env 계정을 갖고 있을 때만 노출한다(값은 받지 않고 boolean만). 조회 실패는 미노출로 처리한다.
const demoEnabled = ref<boolean>(false)
onMounted(async () => {
  try {
    const status = await $fetch<{ enabled: boolean }>(ADMIN_DEMO_STATUS_PATH)
    demoEnabled.value = status.enabled
  } catch (error) {
    console.warn('[admin-demo] status check failed', error)
  }
})

function handleDemoLogin(): Promise<void> {
  return attemptLogin(() => adminAuth.loginDemo())
}

useSeoMeta({ title: '관리자 로그인 · zslab-mall', robots: 'noindex, nofollow' })
</script>

<template>
  <v-row justify="center" align="start" class="fill-height pt-16">
    <v-col cols="12" sm="8" md="5" lg="4" xl="3">
      <div class="adm-page-header text-center mb-6">
        <div class="text-h6 font-weight-bold">zslab-mall</div>
        <div class="adm-page-header__description text-body-2">관리자 콘솔</div>
      </div>
      <v-card class="pa-6">
        <p class="text-body-2 text-medium-emphasis mb-5">관리자 계정으로 로그인하세요.</p>
        <v-form @submit.prevent="handleSubmit">
          <v-text-field id="admin-email" v-model="email" label="이메일" type="email" autocomplete="username" required />
          <v-text-field id="admin-password" v-model="password" label="비밀번호" type="password" autocomplete="current-password" required />
          <v-alert v-if="errorMessage" type="error" class="mb-4" role="alert">{{ errorMessage }}</v-alert>
          <v-btn type="submit" color="primary" size="large" block :loading="submitting" :disabled="submitting">로그인</v-btn>
        </v-form>
        <template v-if="demoEnabled">
          <v-divider class="my-5" />
          <v-btn data-testid="admin-demo-login" variant="outlined" size="large" block :disabled="submitting" @click="handleDemoLogin">관리자 데모 로그인</v-btn>
        </template>
      </v-card>
    </v-col>
  </v-row>
</template>
