<script setup lang="ts">
import { mdiLockOutline } from '@mdi/js'
import {
  SELLER_LOGIN_NOTICE_PASSWORD_CHANGED,
  SELLER_LOGIN_NOTICE_QUERY,
  SELLER_LOGIN_PATH,
} from '#layers/seller/app/lib/constants/auth'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'
import { PASSWORD_CHANGE_FORM_MESSAGES, validatePasswordChangeForm } from '#layers/seller/app/lib/seller-password-form'
import { extractErrorCode, mapFieldErrors, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'
import { PASSWORD_MAX, PASSWORD_MIN } from '~/lib/constants/account'
import type { ChangePasswordRequest } from '~/types/user'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '비밀번호 변경 · zslab-mall 셀러' })

/**
 * 셀러 본인 비밀번호 변경(Track 90-D-2·FE-50). BE PATCH /api/v1/users/me/password(role 무관·anyRequest authenticated)를 seller_token으로 호출한다.
 * 임시 비밀번호 세션(passwordChangeRequired·D-3)은 seller 미들웨어가 다른 화면 진입을 막고 여기로만 보낸다.
 * 성공 204 후 BE가 그 회원의 모든 토큰(역할 무관·credentials_changed_at)을 무효화하므로 seller_token을 지우고 로그인 페이지로 보내 재로그인을 안내한다.
 */
const sellerAuth = useSellerAuthStore()
const sellerApi = useSellerApi()
const toast = useSellerToast()

const currentPassword = ref('')
const newPassword = ref('')
const newPasswordConfirm = ref('')
const errors = ref<Record<string, string>>({})
const submitting = ref(false)

function clearError(field: string): void {
  errors.value = { ...errors.value, [field]: '' }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  const validation = validatePasswordChangeForm({
    currentPassword: currentPassword.value,
    newPassword: newPassword.value,
    newPasswordConfirm: newPasswordConfirm.value,
  })
  errors.value = validation
  if (Object.keys(validation).length > 0) return

  submitting.value = true
  const body: ChangePasswordRequest = { currentPassword: currentPassword.value, newPassword: newPassword.value }
  try {
    await sellerApi<void>('/v1/users/me/password', { method: 'PATCH', body })
    // 변경 이전 발급 토큰은 무효(D-178) → 셀러 세션·강제 상태 쿠키를 지우고 재로그인 안내(구매자 mypage/password.vue 동형)
    sellerAuth.logout()
    await navigateTo(`${SELLER_LOGIN_PATH}?${SELLER_LOGIN_NOTICE_QUERY}=${SELLER_LOGIN_NOTICE_PASSWORD_CHANGED}`)
  } catch (error) {
    const code = extractErrorCode(error)
    if (code === 'MALFORMED_REQUEST') {
      // BE는 현재 비밀번호 불일치·자격증명 없음을 400 MALFORMED_REQUEST로 통합·사유 은닉 → 현재 비밀번호 필드에 표시
      errors.value = { currentPassword: PASSWORD_CHANGE_FORM_MESSAGES.currentPasswordMismatch }
    } else if (code === 'VALIDATION_FAILED') {
      const mapped = mapFieldErrors(error)
      errors.value = Object.keys(mapped).length > 0 ? mapped : { newPassword: toSellerErrorMessage(error) }
    } else {
      toast.danger(toSellerErrorMessage(error))
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div data-testid="seller-password-change">
    <SellerPageHeader title="비밀번호 변경" description="셀러 센터 로그인 비밀번호를 변경합니다." />
    <v-row>
      <v-col cols="12" md="6" lg="5">
        <v-card data-testid="seller-password-form-card">
          <v-card-text class="pa-5">
            <v-alert v-if="sellerAuth.passwordChangeRequired" type="warning" variant="tonal" density="compact" class="mb-4" role="status" data-testid="seller-password-temporary-notice">
              임시 비밀번호로 로그인했습니다. 새 비밀번호로 변경해야 셀러 센터를 이용할 수 있습니다.
            </v-alert>
            <v-alert type="info" variant="tonal" density="compact" :icon="mdiLockOutline" class="mb-4" data-testid="seller-password-logout-notice">
              비밀번호를 변경하면 모든 기기의 셀러 로그인과 같은 계정의 구매자 로그인이 함께 로그아웃되며, 새 비밀번호로 다시 로그인해야 합니다.
            </v-alert>
            <v-form data-testid="seller-password-form" @submit.prevent="submit">
              <v-text-field
                id="seller-current-password"
                v-model="currentPassword"
                label="현재 비밀번호"
                type="password"
                autocomplete="current-password"
                :error-messages="errors.currentPassword ? [errors.currentPassword] : []"
                data-testid="seller-current-password"
                @update:model-value="clearError('currentPassword')"
              />
              <v-text-field
                id="seller-new-password"
                v-model="newPassword"
                label="새 비밀번호"
                type="password"
                autocomplete="new-password"
                :hint="`${PASSWORD_MIN}자 이상 ${PASSWORD_MAX}자 이하`"
                persistent-hint
                :maxlength="PASSWORD_MAX"
                :error-messages="errors.newPassword ? [errors.newPassword] : []"
                class="mb-2"
                data-testid="seller-new-password"
                @update:model-value="clearError('newPassword')"
              />
              <v-text-field
                id="seller-new-password-confirm"
                v-model="newPasswordConfirm"
                label="새 비밀번호 확인"
                type="password"
                autocomplete="new-password"
                :maxlength="PASSWORD_MAX"
                :error-messages="errors.newPasswordConfirm ? [errors.newPasswordConfirm] : []"
                data-testid="seller-new-password-confirm"
                @update:model-value="clearError('newPasswordConfirm')"
              />
              <v-btn type="submit" color="primary" size="large" block :loading="submitting" :disabled="submitting" data-testid="seller-password-submit">
                비밀번호 변경
              </v-btn>
            </v-form>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>
