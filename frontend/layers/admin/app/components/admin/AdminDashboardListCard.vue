<script setup lang="ts" generic="T">
import { mdiChevronRight } from '@mdi/js'

/**
 * 하단 리스트 카드 공통 틀(FE-33): 제목 + "전체 보기" 링크 헤더, 행 슬롯, 빈 상태. 행 내용은 슬롯(row)이 그리고 클릭 이동은 행별 to로 받는다
 * (to가 없으면 클릭 불가 행). rows가 null이면 응답 전이라 빈 문구를 그리지 않는다(처리 대기 칸의 null → "—" · 차트 빈 문구의 data && 가드와 같은 판정 · FE-111).
 */
defineProps<{
  title: string
  allLink: string
  rows: T[] | null
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
  <v-card class="adm-dashboard-list h-100" :data-testid="testId">
    <div class="d-flex align-center justify-space-between px-5 pt-4 pb-2">
      <p class="text-subtitle-1 font-weight-bold mb-0">{{ title }}</p>
      <NuxtLink :to="allLink" class="text-body-2 text-primary text-decoration-none d-inline-flex align-center" :data-testid="`${testId}-all`">
        전체 보기
        <v-icon :icon="mdiChevronRight" size="16" />
      </NuxtLink>
    </div>
    <v-list v-if="rows && rows.length > 0" density="compact" class="pb-2">
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
    <div v-else-if="rows" class="text-body-2 text-medium-emphasis text-center py-8" :data-testid="`${testId}-empty`">
      {{ emptyText ?? '데이터 없음' }}
    </div>
  </v-card>
</template>
