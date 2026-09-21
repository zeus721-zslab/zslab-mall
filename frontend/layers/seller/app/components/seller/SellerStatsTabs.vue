<script setup lang="ts">
import { SELLER_MENU } from '#layers/seller/app/lib/constants/seller-menu'

/**
 * 셀러 통계 탭 이동(Track 90-E-1·관리자 AdminStatsTabs 복제). 탭 목록은 사이드바 메뉴 "통계" 그룹(seller-menu.ts)과 같은 단일 소스에서 읽어
 * 경로가 있는 항목(구현된 탭)만 활성이고 나머지(90-E-2·3)는 비활성으로 둔다. 활성 탭은 현재 경로로 판정하고 이동은 라우터 링크(쿼리는 탭별 URL 단일 소스).
 */
const STATS_GROUP_LABEL = '통계'

const tabs = (SELLER_MENU.find((group) => group.label === STATS_GROUP_LABEL)?.children ?? []).map((item, index) => ({
  key: item.to ?? `pending-${index}`,
  label: item.label,
  to: item.to ?? null,
}))

const route = useRoute()
const active = computed(() => tabs.find((tab) => tab.to !== null && route.path === tab.to)?.to ?? null)
</script>

<template>
  <v-tabs :model-value="active" color="primary" density="comfortable" class="mb-4" data-testid="seller-stats-tabs">
    <v-tab v-for="tab in tabs" :key="tab.key" :value="tab.to ?? tab.key" :to="tab.to ?? undefined" :disabled="tab.to === null" :data-testid="`seller-stats-tab-${tab.key}`">{{ tab.label }}</v-tab>
  </v-tabs>
</template>
