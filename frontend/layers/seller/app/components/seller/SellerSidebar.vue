<script setup lang="ts">
import {
  mdiCartOutline,
  mdiChartBoxOutline,
  mdiCogOutline,
  mdiInboxOutline,
  mdiPackageVariantClosed,
  mdiStorefrontOutline,
  mdiViewDashboardOutline,
  mdiWalletOutline,
} from '@mdi/js'
import { useDisplay } from 'vuetify'
import { SELLER_MENU, type SellerMenuBadge, resolveActiveSellerMenuPath } from '#layers/seller/app/lib/constants/seller-menu'
import { useSellerInboxBadge } from '#layers/seller/app/composables/useSellerInboxBadge'

// 셀러 사이드바(Track 90-A·관리자 AdminSidebar 동형). 열림 상태는 레이아웃이 소유(v-model) — 상단바 토글과 공유.
// 데스크톱(md 이상)은 가장자리 16px 여백의 흰 카드형 고정(seller-sidebar--card), 모바일은 temporary drawer.
// 그룹은 접지 않고 소형 아이콘 배지 + 라벨(헤더) + 하위 항목 나열. 화면이 없는 항목(`to` 없음·90-B 이후)은 비활성으로만 표시한다.
const open = defineModel<boolean>({ required: true })
const { mdAndUp } = useDisplay()
const route = useRoute()

const GROUP_BADGES: Record<string, { icon: string; color: string }> = {
  '인박스': { icon: mdiInboxOutline, color: 'error' },
  '대시보드': { icon: mdiViewDashboardOutline, color: 'primary' },
  '주문': { icon: mdiCartOutline, color: 'warning' },
  '상품': { icon: mdiPackageVariantClosed, color: 'success' },
  '통계': { icon: mdiChartBoxOutline, color: 'info' },
  '정산': { icon: mdiWalletOutline, color: 'error' },
  '설정': { icon: mdiCogOutline, color: 'secondary' },
}

// 메뉴 건수 배지(FE-101): 인박스 오늘 탭 건수. 처음 그릴 때와 창 포커스 때 다시 읽고, 인박스 화면은 목록을 읽을 때 같은 값을 갱신한다.
const inboxBadge = useSellerInboxBadge()
function badgeCount(badge: SellerMenuBadge | undefined): number | null {
  return badge === 'INBOX_TODAY' ? inboxBadge.count.value : null
}
function onWindowFocus(): void {
  void inboxBadge.refresh()
}
onMounted(() => {
  void inboxBadge.refresh()
  window.addEventListener('focus', onWindowFocus)
})
onBeforeUnmount(() => window.removeEventListener('focus', onWindowFocus))

// 활성 판정은 resolveActiveSellerMenuPath 1건만: 정확 일치 우선·하위 경로는 가장 긴 메뉴 경로 1개만 활성(단순 prefix 매칭 금지).
const activeMenuPath = computed<string | null>(() => resolveActiveSellerMenuPath(route.path))
function isActive(to: string): boolean {
  return activeMenuPath.value === to
}
</script>

<template>
  <v-navigation-drawer
    v-model="open"
    :permanent="mdAndUp"
    :temporary="!mdAndUp"
    :width="mdAndUp ? 266 : 250"
    color="surface"
    class="seller-sidebar"
    :class="{ 'seller-sidebar--card': mdAndUp }"
    data-testid="seller-sidebar"
  >
    <v-list-item to="/seller" class="py-3 px-4">
      <template #prepend>
        <v-avatar color="primary" size="32" rounded="lg">
          <v-icon :icon="mdiStorefrontOutline" size="18" color="white" />
        </v-avatar>
      </template>
      <v-list-item-title class="font-weight-bold">zslab-mall</v-list-item-title>
      <v-list-item-subtitle>셀러</v-list-item-subtitle>
    </v-list-item>
    <v-divider class="mx-4" />
    <v-list nav density="compact" aria-label="셀러 메뉴" class="px-2 pt-3">
      <template v-for="group in SELLER_MENU" :key="group.label">
        <template v-if="group.children">
          <div class="slr-group-header">
            <v-avatar :color="GROUP_BADGES[group.label]?.color" size="28" rounded="lg">
              <v-icon :icon="GROUP_BADGES[group.label]?.icon" size="15" color="white" />
            </v-avatar>
            <span>{{ group.label }}</span>
          </div>
          <v-list-item
            v-for="item in group.children"
            :key="item.label"
            :to="item.to"
            :active="item.to ? isActive(item.to) : false"
            :disabled="!item.to"
            exact
            rounded="lg"
            class="slr-leaf"
            :title="item.label"
          />
        </template>
        <v-list-item v-else :to="group.to" :active="group.to ? isActive(group.to) : false" :disabled="!group.to" exact rounded="lg">
          <template #prepend>
            <v-avatar :color="GROUP_BADGES[group.label]?.color" size="28" rounded="lg">
              <v-icon :icon="GROUP_BADGES[group.label]?.icon" size="15" color="white" />
            </v-avatar>
          </template>
          <v-list-item-title>{{ group.label }}</v-list-item-title>
          <template v-if="(badgeCount(group.badge) ?? 0) > 0" #append>
            <v-badge inline color="error" :content="badgeCount(group.badge) ?? 0" data-testid="seller-menu-badge-inbox" />
          </template>
        </v-list-item>
      </template>
    </v-list>
  </v-navigation-drawer>
</template>
