<script setup lang="ts">
import { mdiHelpCircleOutline, mdiLogout, mdiMenu } from '@mdi/js'
import { ADMIN_LOGIN_PATH } from '#layers/admin/app/lib/constants/auth'
import { ADMIN_MENU, resolveActiveMenuPath } from '#layers/admin/app/lib/constants/admin-menu'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'
import type { Profile } from '~/types/user'

// 상단바(FE-22·D-5·FE-22c Vuetify·FE-22e·FE-22f 밴드 위 투명): 좌측 브레드크럼(admin-menu 기준 그룹 › 메뉴), 우측 아바타 메뉴(이름/이메일·로그아웃).
// 관리자 식별은 GET /users/me(publicId·email·name)만 쓴다. 세분 역할(SUPER_ADMIN 여부)은 BE 조회 API 부재로 미표시.
const emit = defineEmits<{ toggleSidebar: [] }>()
const adminAuth = useAdminAuthStore()
const adminApi = useAdminApi()
const route = useRoute()

// 조회 실패(401은 useAdminApi가 로그인으로 보냄·그 외)는 상단바 표시만 비우고 셸 렌더는 계속한다.
const { data: profile, error: profileError } = useAsyncData<Profile>('admin-profile', () => adminApi<Profile>('/v1/users/me'))
const displayName = computed<string>(() => {
  if (profileError.value || !profile.value) return ''
  return profile.value.name || profile.value.email
})
// 이름이 있을 때만 이메일을 부제로(이름 없는 부트스트랩 계정은 제목이 이미 이메일이라 중복 표시 방지)
const displayEmail = computed<string>(() => (profileError.value || !profile.value?.name ? '' : profile.value.email))
// 아바타 이니셜: 이름(또는 이메일) 첫 글자. 프로필 미도착 시 빈 아바타.
const avatarInitial = computed<string>(() => displayName.value.charAt(0).toUpperCase())

// 브레드크럼: 현재 경로가 속한 그룹 › 메뉴. 대시보드(단일 링크)는 1단계. 하위 경로(/admin/products/prd_…)는 상위 메뉴로 해석(FE-25). 메뉴 밖 경로는 빈 배열.
const breadcrumbs = computed<string[]>(() => {
  const activePath = resolveActiveMenuPath(route.path)
  for (const group of ADMIN_MENU) {
    if (group.to === activePath) return [group.label]
    const item = group.children?.find((child) => child.to === activePath)
    if (item) return [group.label, item.label]
  }
  return []
})

// 로그아웃: admin_token만 비운다(사용자 auth_token·cart 무관·FE-22d). 대상이 /admin/login이라 이탈 가드(전체 새로고침) 미발동·이후 /admin/**는 미들웨어가 다시 가드한다.
async function handleLogout(): Promise<void> {
  adminAuth.logout()
  await navigateTo(ADMIN_LOGIN_PATH)
}
</script>

<template>
  <!-- 연한 밴드 위 투명 상단바(FE-22f·FE-22g): 텍스트·아이콘은 어두운 색 -->
  <v-app-bar color="transparent" flat height="56" class="admin-topbar" data-testid="admin-topbar">
    <v-app-bar-nav-icon :icon="mdiMenu" size="small" aria-label="메뉴 열기/닫기" data-testid="admin-sidebar-toggle" @click="emit('toggleSidebar')" />
    <v-breadcrumbs :items="breadcrumbs" divider="›" density="compact" class="admin-breadcrumbs pl-1" aria-label="현재 위치">
      <template #item="{ item, index }">
        <span :class="index === breadcrumbs.length - 1 ? 'font-weight-bold' : 'adm-breadcrumb-muted'">{{ item.title }}</span>
      </template>
    </v-breadcrumbs>
    <template #append>
      <v-menu>
        <template #activator="{ props: activatorProps }">
          <v-btn v-bind="activatorProps" icon variant="text" size="small" aria-label="계정 메뉴" data-testid="admin-account-menu">
            <v-avatar color="primary" size="30">
              <span class="text-body-2 font-weight-medium">{{ avatarInitial }}</span>
            </v-avatar>
          </v-btn>
        </template>
        <v-list min-width="220" data-testid="admin-account-menu-content">
          <v-list-item v-if="displayName" :title="displayName" :subtitle="displayEmail || undefined" data-testid="admin-display-name" />
          <v-divider v-if="displayName" class="my-1" />
          <v-list-item :prepend-icon="mdiHelpCircleOutline" title="도움말" to="/admin/help" data-testid="admin-help-link" />
          <v-list-item :prepend-icon="mdiLogout" title="로그아웃" data-testid="admin-logout" @click="handleLogout" />
        </v-list>
      </v-menu>
    </template>
  </v-app-bar>
</template>
