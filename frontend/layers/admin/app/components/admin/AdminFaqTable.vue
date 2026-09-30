<script setup lang="ts">
import { mdiArrowDown, mdiArrowUp, mdiPencilOutline, mdiTrashCanOutline } from '@mdi/js'
import type { AdminFaqItem } from '#layers/admin/app/types/admin-faq'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 한 카테고리의 FAQ 표(Track 106-3 · AdminCategoryTable 선례 · 페이징 없음). 순서는 위/아래 버튼으로 바꾼다(드래그 라이브러리 미사용).
 * 정렬 요청 중에는 이동 버튼을 잠근다(연타로 순서 어긋남 방지).
 */
const props = defineProps<{
  items: AdminFaqItem[]
  loading: boolean
  reordering: boolean
}>()

const emit = defineEmits<{
  move: [index: number, direction: -1 | 1]
  edit: [item: AdminFaqItem]
  remove: [item: AdminFaqItem]
}>()

const headers = [
  { title: '순서', key: 'order', sortable: false, width: 120 },
  { title: '질문', key: 'question', sortable: false },
  { title: '공개', key: 'visible', sortable: false, width: 96 },
  { title: '수정일', key: 'updatedAt', sortable: false, width: 180 },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const, width: 110 },
]

function isLast(index: number): boolean {
  return index === props.items.length - 1
}
</script>

<template>
  <v-data-table
    :headers="headers"
    :items="items"
    :loading="loading"
    loading-text="불러오는 중…"
    item-value="id"
    items-per-page="-1"
    hide-default-footer
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-faq-table"
  >
    <template #[`item.order`]="{ index }">
      <div class="d-flex align-center ga-1">
        <span class="text-medium-emphasis mr-1" data-testid="row-order">{{ index + 1 }}</span>
        <v-btn :icon="mdiArrowUp" size="x-small" variant="text" :disabled="reordering || index === 0" aria-label="위로" data-testid="row-move-up" @click="emit('move', index, -1)" />
        <v-btn :icon="mdiArrowDown" size="x-small" variant="text" :disabled="reordering || isLast(index)" aria-label="아래로" data-testid="row-move-down" @click="emit('move', index, 1)" />
      </div>
    </template>
    <template #[`item.question`]="{ item }">
      <span class="font-weight-medium" data-testid="row-question">{{ item.question }}</span>
    </template>
    <template #[`item.visible`]="{ item }">
      <v-chip size="small" :color="item.visible ? 'success' : undefined" variant="tonal" data-testid="row-visible">
        {{ item.visible ? '공개' : '숨김' }}
      </v-chip>
    </template>
    <template #[`item.updatedAt`]="{ item }">
      {{ formatDateTime(item.updatedAt) }}
    </template>
    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn :icon="mdiPencilOutline" size="small" variant="text" aria-label="수정" data-testid="row-edit" @click="emit('edit', item)" />
        <v-btn :icon="mdiTrashCanOutline" size="small" variant="text" color="error" aria-label="삭제" data-testid="row-delete" @click="emit('remove', item)" />
      </div>
    </template>
    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table>
</template>
