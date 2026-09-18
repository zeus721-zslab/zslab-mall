<script setup lang="ts">
/**
 * 통계 3탭 이동(FE-35). /admin/stats 하위 매출·주문·회원 화면 상단에 같은 탭을 두어 서로 오간다(좌측 메뉴 항목과 동일 경로·"상품"은 미구현이라 제외).
 * 활성 탭은 현재 경로로 판정하고 이동은 라우터 링크(쿼리는 탭별 URL 단일 소스라 넘기지 않는다).
 */
const STATS_TABS: { to: string; label: string; key: string }[] = [
  { to: '/admin/stats/sales', label: '매출', key: 'sales' },
  { to: '/admin/stats/orders', label: '주문·클레임', key: 'orders' },
  { to: '/admin/stats/members', label: '회원', key: 'members' },
]

const route = useRoute()
const active = computed(() => STATS_TABS.find((tab) => route.path === tab.to)?.to ?? null)
</script>

<template>
  <v-tabs :model-value="active" color="primary" density="comfortable" class="mb-4" data-testid="stats-tabs">
    <v-tab v-for="tab in STATS_TABS" :key="tab.key" :value="tab.to" :to="tab.to" :data-testid="`stats-tab-${tab.key}`">{{ tab.label }}</v-tab>
  </v-tabs>
</template>
