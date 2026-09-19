<script setup lang="ts">
import { mdiLockAlertOutline, mdiLogout, mdiOpenInNew } from '@mdi/js'
import { SELLER_LOGIN_PATH } from '#layers/seller/app/lib/constants/auth'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '비밀번호 변경 · zslab-mall 셀러' })

// 셀러 비밀번호 변경 안내(Track 90-A placeholder·D-3). 임시 비밀번호 세션은 seller 미들웨어가 다른 셀러 화면 진입을 막고 여기로만 보낸다.
// 실제 변경 폼은 90-D 설정 화면에서 제공. 그때까지 구매자 경로로 해소: 같은 회원 계정이므로 구매자 페이지(/mypage/password)에서 비밀번호를
// 바꾸면 BE의 user 단위 passwordChangeRequired가 풀리고, 셀러로 다시 로그인하면 강제 상태가 해제된다. 탈출구로 로그아웃 버튼을 둔다(외부 검토 R1).
const sellerAuth = useSellerAuthStore()

/** 구매자 비밀번호 변경 페이지. 새 탭(전체 로드)이라 셀러 문서·Vuetify 스타일이 사용자 화면으로 누수되지 않는다. */
const BUYER_PASSWORD_CHANGE_PATH = '/mypage/password'

async function handleLogout(): Promise<void> {
  sellerAuth.logout()
  await navigateTo(SELLER_LOGIN_PATH)
}
</script>

<template>
  <div data-testid="seller-password-change">
    <SellerPageHeader title="비밀번호 변경" description="임시 비밀번호로 로그인한 계정은 비밀번호를 변경한 뒤 셀러 센터를 이용할 수 있습니다." />
    <v-card data-testid="seller-password-placeholder">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <v-avatar color="surface-variant" size="48" class="mb-4">
          <v-icon :icon="mdiLockAlertOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-1 font-weight-medium mb-1">임시 비밀번호 상태입니다</p>
        <p class="text-body-2 text-medium-emphasis mb-1">보안을 위해 비밀번호를 변경해야 셀러 센터를 이용할 수 있습니다. 셀러 센터의 변경 화면은 준비 중입니다.</p>
        <p class="text-body-2 text-medium-emphasis mb-5" data-testid="seller-password-guide">
          구매자 페이지(/mypage/password)에서 비밀번호를 변경한 뒤 다시 로그인해 주세요. 같은 계정이므로 변경 즉시 셀러 로그인에도 적용됩니다.
        </p>
        <div class="d-flex flex-wrap justify-center ga-2">
          <v-btn color="primary" :href="BUYER_PASSWORD_CHANGE_PATH" target="_blank" rel="noopener" :append-icon="mdiOpenInNew" data-testid="seller-password-buyer-link">
            구매자 페이지에서 비밀번호 변경
          </v-btn>
          <v-btn variant="outlined" :prepend-icon="mdiLogout" data-testid="seller-password-logout" @click="handleLogout">로그아웃</v-btn>
        </div>
      </v-card-text>
    </v-card>
  </div>
</template>
