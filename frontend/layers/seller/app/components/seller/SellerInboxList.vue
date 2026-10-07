<script setup lang="ts">
import { mdiAlertCircleOutline, mdiRefresh } from '@mdi/js'
import type { InboxTypeCount } from '~/types/inbox'
import { INBOX_AUDIENCE_TYPES, INBOX_TAB_LABELS, type InboxItemType, type InboxTab, inboxItemTypeLabel } from '~/lib/constants/inbox'
import { INBOX_TRUNCATED_MESSAGE, type InboxItem, inboxDeadline, inboxEmptyMessage } from '~/lib/inbox-view'
import { sellerInboxDeadlineChipClass } from '#layers/seller/app/lib/seller-inbox-view'

/**
 * 셀러 인박스 목록(D-248 · 관리자 AdminInboxList 복제 · 레이어 격리). 탭 · 유형 필터 칩(건수) · 기한 순 항목. 데이터·선택은 페이지가 소유한다.
 */
const props = defineProps<{
  items: InboxItem[]
  counts: InboxTypeCount[]
  tab: InboxTab
  type: InboxItemType | null
  selectedKey: string | null
  loading: boolean
  error: string | null
  truncated: boolean
  nowMs: number
}>()
const emit = defineEmits<{
  'update:tab': [tab: InboxTab]
  'update:type': [type: InboxItemType | null]
  select: [key: string]
  retry: []
  refresh: []
}>()

const TABS: InboxTab[] = ['TODAY', 'UPCOMING']
/** 테마 primary(#0D9488)는 흰 글자 대비 3.74:1이라 선택 칩만 진한 색으로 바꾼다(FE-112 · 테마 전체 변경은 기각). */
const SELECTED_CHIP_COLOR = '#0F766E'

/** BE는 보이는 유형마다 항상 건수를 주므로 빈 배열은 응답 전이다 — 그때만 건수를 숨긴다(오류 시엔 이전 값이 남는다). */
const countsKnown = computed(() => props.counts.length > 0)
function chipLabel(label: string, count: number): string {
  return countsKnown.value ? `${label} ${count}` : label
}
const countByType = computed<Map<InboxItemType, number>>(() => new Map(props.counts.map((count) => [count.type, count.count])))
const totalCount = computed(() => props.counts.reduce((sum, count) => sum + count.count, 0))
const showSkeleton = computed(() => props.loading && props.items.length === 0)

function onTab(value: unknown): void {
  if (value === 'TODAY' || value === 'UPCOMING') emit('update:tab', value)
}
</script>

<template>
  <v-card data-testid="inbox-list">
    <div class="d-flex align-center px-2 pt-1">
      <v-tabs :model-value="tab" density="comfortable" color="primary" class="flex-grow-1" @update:model-value="onTab">
        <v-tab v-for="value in TABS" :key="value" :value="value" :data-testid="`inbox-tab-${value}`">{{ INBOX_TAB_LABELS[value] }}</v-tab>
      </v-tabs>
      <v-btn :icon="mdiRefresh" variant="text" size="small" :loading="loading" aria-label="새로고침" data-testid="inbox-refresh" @click="emit('refresh')" />
    </div>
    <v-divider />
    <div class="d-flex flex-wrap ga-2 px-4 py-3" data-testid="inbox-type-chips">
      <v-chip
        size="small"
        :variant="type === null ? 'flat' : 'outlined'"
        :color="type === null ? SELECTED_CHIP_COLOR : undefined"
        data-testid="inbox-type-chip-ALL"
        @click="emit('update:type', null)"
      >{{ chipLabel('전체', totalCount) }}</v-chip>
      <v-chip
        v-for="value in INBOX_AUDIENCE_TYPES.SELLER"
        :key="value"
        size="small"
        :variant="type === value ? 'flat' : 'outlined'"
        :color="type === value ? SELECTED_CHIP_COLOR : undefined"
        :data-testid="`inbox-type-chip-${value}`"
        @click="emit('update:type', value)"
      >{{ chipLabel(inboxItemTypeLabel(value), countByType.get(value) ?? 0) }}</v-chip>
    </div>
    <v-divider />

    <v-alert v-if="error" type="error" variant="tonal" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="inbox-error">
      {{ error }}
      <template #append>
        <v-btn size="small" variant="text" data-testid="inbox-retry" @click="emit('retry')">다시 시도</v-btn>
      </template>
    </v-alert>
    <v-alert v-if="truncated" type="info" variant="tonal" density="compact" class="ma-4" data-testid="inbox-truncated">
      {{ INBOX_TRUNCATED_MESSAGE }}
    </v-alert>

    <v-skeleton-loader v-if="showSkeleton" type="list-item-two-line@5" data-testid="inbox-loading" />
    <div v-else-if="!error && items.length === 0" class="text-body-2 text-medium-emphasis text-center py-10" data-testid="inbox-empty">
      {{ inboxEmptyMessage(tab, type) }}
    </div>
    <v-list v-else-if="items.length > 0" lines="two" class="py-0" data-testid="inbox-items">
      <v-list-item
        v-for="item in items"
        :key="item.key"
        :active="item.key === selectedKey"
        color="primary"
        class="slr-inbox-item"
        :class="{ 'slr-inbox-item--overdue': item.overdue }"
        data-testid="inbox-item"
        :data-key="item.key"
        @click="emit('select', item.key)"
      >
        <v-list-item-title class="text-body-2 font-weight-medium">{{ item.title }}</v-list-item-title>
        <v-list-item-subtitle class="slr-inbox-subtitle">
          <span>{{ inboxItemTypeLabel(item.type) }}</span>
          <span v-if="item.subtitle"> · {{ item.subtitle }}</span>
        </v-list-item-subtitle>
        <template #append>
          <span :class="sellerInboxDeadlineChipClass(inboxDeadline(item.dueAt, nowMs).tone)" data-testid="inbox-item-deadline">
            {{ inboxDeadline(item.dueAt, nowMs).text }}
          </span>
        </template>
      </v-list-item>
    </v-list>
  </v-card>
</template>

<style scoped>
.slr-inbox-item {
  border-left: 3px solid transparent;
}
.slr-inbox-item--overdue {
  border-left-color: rgb(var(--v-theme-error));
}
/* Vuetify 보조 문구 투명도(medium-emphasis)가 문구와 배지에 곱해져 대비가 기준 미달 — 투명도 대신 색으로 약하게 한다(UX-03). */
.slr-inbox-subtitle {
  opacity: 1;
  color: rgba(var(--v-theme-on-surface), 0.7);
}
</style>
