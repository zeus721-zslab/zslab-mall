<script setup lang="ts">
import {
  mdiAccountGroupOutline,
  mdiCartOutline,
  mdiChartBoxOutline,
  mdiPackageVariantClosed,
  mdiStorefrontOutline,
  mdiViewDashboardOutline,
  mdiWalletOutline,
} from '@mdi/js'
import { useDisplay } from 'vuetify'
import { ADMIN_MENU } from '#layers/admin/app/lib/constants/admin-menu'

// 사이드바(FE-22c Vuetify·FE-22f Argon형). 열림 상태는 레이아웃이 소유(v-model) — 상단바 토글과 공유.
// 데스크톱(md 이상)은 가장자리 16px 여백의 흰 카드형 고정(admin-sidebar--card), 모바일은 기존 temporary drawer.
// 그룹은 접지 않고 색이 다른 소형 아이콘 배지 + 라벨(헤더) + 하위 항목 나열(메뉴 구조·순서는 admin-menu.ts 그대로). 아이콘·색은 표시 전용이라 여기서 라벨에 매핑한다.
const open = defineModel<boolean>({ required: true })
const { mdAndUp } = useDisplay()
const route = useRoute()

const GROUP_BADGES: Record<string, { icon: string; color: string }> = {
  '대시보드': { icon: mdiViewDashboardOutline, color: 'primary' },
  '회원 관리': { icon: mdiAccountGroupOutline, color: 'info' },
  '주문 관리': { icon: mdiCartOutline, color: 'warning' },
  '상품 관리': { icon: mdiPackageVariantClosed, color: 'success' },
  '정산 관리': { icon: mdiWalletOutline, color: 'error' },
  '통계': { icon: mdiChartBoxOutline, color: 'secondary' },
}

// 활성 판정은 정확 일치(exact)만 쓴다(prefix 매칭이면 /admin/orders가 /admin/orders/payments에서도 활성돼 두 항목이 동시에 강조됨).
function isActive(to: string): boolean {
  return route.path === to
}
</script>

<template>
  <v-navigation-drawer
    v-model="open"
    :permanent="mdAndUp"
    :temporary="!mdAndUp"
    :width="mdAndUp ? 266 : 250"
    color="surface"
    class="admin-sidebar"
    :class="{ 'admin-sidebar--card': mdAndUp }"
    data-testid="admin-sidebar"
  >
    <v-list-item to="/admin" class="py-3 px-4">
      <template #prepend>
        <v-avatar color="primary" size="32" rounded="lg">
          <v-icon :icon="mdiStorefrontOutline" size="18" color="white" />
        </v-avatar>
      </template>
      <v-list-item-title class="font-weight-bold">zslab-mall</v-list-item-title>
      <v-list-item-subtitle>관리자</v-list-item-subtitle>
    </v-list-item>
    <v-divider class="mx-4" />
    <v-list nav density="compact" aria-label="관리자 메뉴" class="px-2 pt-3">
      <template v-for="group in ADMIN_MENU" :key="group.label">
        <v-list-item v-if="group.to" :to="group.to" :active="isActive(group.to)" exact rounded="lg">
          <template #prepend>
            <v-avatar :color="GROUP_BADGES[group.label]?.color" size="28" rounded="lg">
              <v-icon :icon="GROUP_BADGES[group.label]?.icon" size="15" color="white" />
            </v-avatar>
          </template>
          <v-list-item-title>{{ group.label }}</v-list-item-title>
        </v-list-item>
        <template v-else-if="group.children">
          <div class="adm-group-header">
            <v-avatar :color="GROUP_BADGES[group.label]?.color" size="28" rounded="lg">
              <v-icon :icon="GROUP_BADGES[group.label]?.icon" size="15" color="white" />
            </v-avatar>
            <span>{{ group.label }}</span>
          </div>
          <v-list-item
            v-for="item in group.children"
            :key="item.to"
            :to="item.to"
            :active="isActive(item.to)"
            exact
            rounded="lg"
            class="adm-leaf"
            :title="item.label"
          />
        </template>
      </template>
    </v-list>
  </v-navigation-drawer>
</template>
