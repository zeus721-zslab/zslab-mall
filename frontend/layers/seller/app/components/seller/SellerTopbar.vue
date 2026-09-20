<script setup lang="ts">
import { mdiLogout, mdiMenu } from '@mdi/js'
import { SELLER_LOGIN_PATH } from '#layers/seller/app/lib/constants/auth'
import { SELLER_MENU, resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'
import { useSellerAuthStore } from '#layers/seller/app/stores/sellerAuth'
import { useSellerMe } from '#layers/seller/app/composables/useSellerMe'
import { SELLER_ROLE_LABEL, SELLER_STATUS_LABEL, SELLER_STATUS_SEMANTIC } from '#layers/seller/app/lib/constants/seller-me'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'
import type { Profile } from '~/types/user'

// 셀러 상단바(Track 90-A·관리자 AdminTopbar 동형): 좌측 브레드크럼(seller-menu 기준 그룹 › 메뉴), 우측 상호·상태 chip + 아바타 메뉴(이름/이메일·역할·로그아웃).
// 계정 식별은 GET /users/me(publicId·email·name)·소속 셀러(상호·상태·역할)는 GET /seller/me(D-191·레이아웃이 로드한 useSellerMe 공유 상태).
const emit = defineEmits<{ toggleSidebar: [] }>()
const sellerAuth = useSellerAuthStore()
const sellerApi = useSellerApi()
const route = useRoute()
const { me: sellerMe } = useSellerMe()

const companyName = computed<string>(() => sellerMe.value?.companyName ?? '')
const statusLabel = computed<string>(() => (sellerMe.value ? SELLER_STATUS_LABEL[sellerMe.value.status] : ''))
const statusChipClass = computed<string>(() => (sellerMe.value ? semanticChipClass(SELLER_STATUS_SEMANTIC[sellerMe.value.status]) : ''))
const roleLabel = computed<string>(() => (sellerMe.value ? SELLER_ROLE_LABEL[sellerMe.value.roleCode] : ''))

// 조회 실패(401은 useSellerApi가 로그인으로 보냄·그 외)는 상단바 표시만 비우고 셸 렌더는 계속한다.
const { data: profile, error: profileError } = useAsyncData<Profile>('seller-profile', () => sellerApi<Profile>('/v1/users/me'))
const displayName = computed<string>(() => {
  if (profileError.value || !profile.value) return ''
  return profile.value.name || profile.value.email
})
// 이름이 있을 때만 이메일을 부제로(제목이 이미 이메일이면 중복 표시 방지)
const displayEmail = computed<string>(() => (profileError.value || !profile.value?.name ? '' : profile.value.email))
// 아바타 이니셜: 이름(또는 이메일) 첫 글자. 프로필 미도착 시 빈 아바타.
const avatarInitial = computed<string>(() => displayName.value.charAt(0).toUpperCase())

// 브레드크럼: 현재 경로가 속한 그룹 › 메뉴. 대시보드(단일 링크)는 1단계. 메뉴 밖 경로(로그인 등)는 빈 배열.
const breadcrumbs = computed<string[]>(() => {
  const activePath = resolveActiveSellerMenuPath(route.path)
  for (const group of SELLER_MENU) {
    if (group.to === activePath) return [group.label]
    const item = group.children?.find((child) => child.to === activePath)
    if (item) return [group.label, item.label]
  }
  return []
})

// 로그아웃: seller_token만 비운다(auth_token·admin_token 무관). 대상이 /seller/login이라 이탈 가드(전체 새로고침) 미발동·이후 /seller/**는 미들웨어가 다시 가드한다.
async function handleLogout(): Promise<void> {
  sellerAuth.logout()
  await navigateTo(SELLER_LOGIN_PATH)
}
</script>

<template>
  <!-- 연한 밴드 위 투명 상단바: 텍스트·아이콘은 어두운 색 -->
  <v-app-bar color="transparent" flat height="56" class="seller-topbar" data-testid="seller-topbar">
    <v-app-bar-nav-icon :icon="mdiMenu" size="small" aria-label="메뉴 열기/닫기" data-testid="seller-sidebar-toggle" @click="emit('toggleSidebar')" />
    <v-breadcrumbs :items="breadcrumbs" divider="›" density="compact" class="seller-breadcrumbs pl-1" aria-label="현재 위치">
      <template #item="{ item, index }">
        <span :class="index === breadcrumbs.length - 1 ? 'font-weight-bold' : 'slr-breadcrumb-muted'">{{ item.title }}</span>
      </template>
    </v-breadcrumbs>
    <template #append>
      <!-- 소속 셀러 상호·상태(GET /seller/me). 미도착·실패 시 비운다. 정지(SUSPENDED)는 danger chip이며 레이아웃 배너가 상세 안내를 맡는다. -->
      <div v-if="companyName" class="d-flex align-center ga-2 mr-2" data-testid="seller-company">
        <span class="text-body-2 font-weight-medium d-none d-sm-inline" data-testid="seller-company-name">{{ companyName }}</span>
        <v-chip :class="statusChipClass" size="x-small" variant="flat" data-testid="seller-status-chip">{{ statusLabel }}</v-chip>
      </div>
      <v-menu>
        <template #activator="{ props: activatorProps }">
          <v-btn v-bind="activatorProps" icon variant="text" size="small" aria-label="계정 메뉴" data-testid="seller-account-menu">
            <v-avatar color="primary" size="30">
              <span class="text-body-2 font-weight-medium">{{ avatarInitial }}</span>
            </v-avatar>
          </v-btn>
        </template>
        <v-list min-width="220" data-testid="seller-account-menu-content">
          <v-list-item v-if="displayName" :title="displayName" :subtitle="displayEmail || undefined" data-testid="seller-display-name" />
          <v-list-item v-if="companyName" :title="companyName" :subtitle="roleLabel || undefined" data-testid="seller-membership" />
          <v-divider v-if="displayName || companyName" class="my-1" />
          <v-list-item :prepend-icon="mdiLogout" title="로그아웃" data-testid="seller-logout" @click="handleLogout" />
        </v-list>
      </v-menu>
    </template>
  </v-app-bar>
</template>
