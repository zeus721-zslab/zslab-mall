<script setup lang="ts">
// 통계 카드(FE-22f·대시보드 트랙용 공통 컴포넌트). 라벨·수치·보조 문구 + 우측 그라데이션 원형 아이콘. 색 키는 admin-vuetify.css .adm-grad-* 와 1:1.
// 기본 슬롯(FE-33)은 캡션 아래 한 줄(증감 배지 등)·미사용 시 렌더 없음.
type StatCardColor = 'primary' | 'success' | 'info' | 'warning' | 'error'

defineProps<{
  label: string
  value: string
  caption?: string
  icon: string
  color?: StatCardColor
}>()
</script>

<template>
  <v-card data-testid="admin-stat-card">
    <v-card-text class="d-flex align-center justify-space-between pa-5">
      <div>
        <p class="text-caption text-medium-emphasis font-weight-medium mb-1">{{ label }}</p>
        <p class="text-h6 font-weight-bold mb-0" data-testid="admin-stat-card-value">{{ value }}</p>
        <p v-if="caption" class="text-body-2 text-medium-emphasis mt-1 mb-0">{{ caption }}</p>
        <div v-if="$slots.default" class="mt-2">
          <slot />
        </div>
      </div>
      <v-avatar size="48" :class="`adm-grad-${color ?? 'primary'}`">
        <v-icon :icon="icon" size="22" color="white" />
      </v-avatar>
    </v-card-text>
  </v-card>
</template>
