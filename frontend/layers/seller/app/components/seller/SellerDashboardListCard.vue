<script setup lang="ts" generic="T">
import { mdiChevronRight } from '@mdi/js'

/**
 * 하단 리스트 카드 공통 틀(Track 90-B-3·관리자 AdminDashboardListCard 복제): 제목 + "전체 보기" 링크(없으면 헤더만), 행 슬롯, 빈 상태.
 * 행 내용은 슬롯(row)이 그리고 클릭 이동은 행별 to로 받는다(to가 없으면 클릭 불가 행 — 최근 클레임은 상세 화면이 없어 링크하지 않는다·90-D).
 */
defineProps<{
  title: string
  allLink?: string
  rows: T[]
  rowKey: (row: T) => string
  rowTo?: (row: T) => string | null
  emptyText?: string
  testId: string
}>()

defineSlots<{
  row(props: { row: T }): unknown
}>()
</script>

<template>
  <v-card class="slr-dashboard-list h-100" :data-testid="testId">
    <div class="d-flex align-center justify-space-between px-5 pt-4 pb-2">
      <p class="text-subtitle-1 font-weight-bold mb-0">{{ title }}</p>
      <NuxtLink v-if="allLink" :to="allLink" class="text-body-2 text-primary text-decoration-none d-inline-flex align-center" :data-testid="`${testId}-all`">
        전체 보기
        <v-icon :icon="mdiChevronRight" size="16" />
      </NuxtLink>
    </div>
    <v-list v-if="rows.length > 0" density="compact" class="pb-2">
      <v-list-item
        v-for="row in rows"
        :key="rowKey(row)"
        :to="rowTo?.(row) ?? undefined"
        :link="Boolean(rowTo?.(row))"
        class="px-5"
        :data-testid="`${testId}-row`"
      >
        <slot name="row" :row="row" />
      </v-list-item>
    </v-list>
    <div v-else class="text-body-2 text-medium-emphasis text-center py-8" :data-testid="`${testId}-empty`">
      {{ emptyText ?? '데이터 없음' }}
    </div>
  </v-card>
</template>
