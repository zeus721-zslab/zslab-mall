<script setup lang="ts">
import { mdiArrowDown, mdiArrowUp, mdiPencilOutline, mdiTrashCanOutline } from '@mdi/js'
import type { AdminCategorySummary } from '#layers/admin/app/types/admin-category'
import { formatDateTime } from '~/lib/utils/datetime'
import { canDeleteCategory, deleteBlockedReason, formatCommissionRate } from '#layers/admin/app/lib/admin-category-view'

/**
 * 카테고리 표(FE-38·6건 규모·페이징 없음·v-data-table). 순서는 위/아래 버튼으로 바꾼다(드래그 라이브러리 미도입). 상품 수 클릭 → 상품 목록
 * 카테고리 필터. 삭제는 상품 0건 행만 활성·아니면 비활성 + 툴팁("연결된 상품 N건"). 미설정 율은 기본율을 병기한다.
 */
const props = defineProps<{
  items: AdminCategorySummary[]
  defaultCommissionRate: number
  loading: boolean
  /** 정렬 변경 요청 중에는 이동 버튼을 잠근다(연타로 순서 어긋남 방지). */
  reordering: boolean
}>()

const emit = defineEmits<{
  move: [index: number, direction: -1 | 1]
  edit: [item: AdminCategorySummary]
  remove: [item: AdminCategorySummary]
  openProducts: [item: AdminCategorySummary]
}>()

const headers = [
  { title: '순서', key: 'order', sortable: false, width: 120 },
  { title: '카테고리명', key: 'displayName', sortable: false },
  { title: '수수료율', key: 'commissionRate', sortable: false },
  { title: '상품 수', key: 'productCount', sortable: false, align: 'end' as const },
  { title: '등록일', key: 'createdAt', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

function isFirst(index: number): boolean {
  return index === 0
}

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
    item-value="categoryId"
    items-per-page="-1"
    hide-default-footer
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-category-table"
  >
    <template #[`item.order`]="{ index }">
      <div class="d-flex align-center ga-1">
        <span class="text-medium-emphasis mr-1" data-testid="row-order">{{ index + 1 }}</span>
        <v-btn :icon="mdiArrowUp" size="x-small" variant="text" :disabled="reordering || isFirst(index)" aria-label="위로" data-testid="row-move-up" @click="emit('move', index, -1)" />
        <v-btn :icon="mdiArrowDown" size="x-small" variant="text" :disabled="reordering || isLast(index)" aria-label="아래로" data-testid="row-move-down" @click="emit('move', index, 1)" />
      </div>
    </template>
    <template #[`item.displayName`]="{ item }">
      <span class="font-weight-medium" data-testid="row-name">{{ item.displayName }}</span>
    </template>
    <template #[`item.commissionRate`]="{ item }">
      <span :class="item.commissionRate == null ? 'text-medium-emphasis' : ''" data-testid="row-commission-rate">
        {{ formatCommissionRate(item.commissionRate, defaultCommissionRate) }}
      </span>
    </template>
    <template #[`item.productCount`]="{ item }">
      <a
        v-if="item.productCount > 0"
        href="#"
        class="text-primary text-decoration-none"
        data-testid="row-product-count"
        @click.prevent="emit('openProducts', item)"
      >{{ item.productCount.toLocaleString('ko-KR') }}</a>
      <span v-else class="text-medium-emphasis" data-testid="row-product-count">0</span>
    </template>
    <template #[`item.createdAt`]="{ item }">
      {{ formatDateTime(item.createdAt) }}
    </template>
    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn :icon="mdiPencilOutline" size="small" variant="text" aria-label="수정" data-testid="row-edit" @click="emit('edit', item)" />
        <!-- 비활성 버튼은 이벤트를 받지 않으므로 툴팁은 감싸는 span에 건다(AdminDeliveryDetailDialog 패턴) -->
        <v-tooltip :disabled="canDeleteCategory(item)" location="top">
          <template #activator="{ props: tooltipProps }">
            <span v-bind="tooltipProps" data-testid="row-delete-wrapper">
              <v-btn
                :icon="mdiTrashCanOutline"
                size="small"
                variant="text"
                color="error"
                :disabled="!canDeleteCategory(item)"
                aria-label="삭제"
                data-testid="row-delete"
                @click="emit('remove', item)"
              />
            </span>
          </template>
          <span data-testid="row-delete-blocked">{{ deleteBlockedReason(item) }}</span>
        </v-tooltip>
      </div>
    </template>
    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table>
</template>
