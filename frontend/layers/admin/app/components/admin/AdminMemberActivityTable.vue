<script setup lang="ts">
import { mdiAlertCircleOutline, mdiReceiptTextOutline } from '@mdi/js'
import type { AdminSemantic } from '#layers/admin/app/lib/constants/semantic'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { ADMIN_MEMBER_PAGE_SIZES } from '#layers/admin/app/lib/constants/admin-member'
import { formatWon } from '#layers/admin/app/lib/format'
import { formatDateTime } from '~/lib/utils/datetime'

/** 회원 상세 주문정보 탭 공용 행(주문·클레임을 같은 최소 컬럼으로 정규화: 번호·주문번호·상태·금액·일시). */
export interface AdminMemberActivityRow {
  key: string
  /** 주문 상세 이동 대상. 없으면(클레임의 주문 미해소) 링크 비활성. */
  orderId?: string
  orderNo: string
  /** 클레임은 상품명 캡션을 덧붙인다(주문은 상품 요약). */
  caption?: string
  statusLabel: string
  statusSemantic: AdminSemantic
  amount?: number
  at: string
}

/**
 * 회원 상세 주문정보 탭 표(Track 84 FE). 기존 AdminOrderTable·AdminClaimTable은 행 액션 메뉴(송장·승인 등)와 다이얼로그 배선이 묶여 있어
 * 읽기 전용 탭에 그대로 쓰면 동작 없는 메뉴가 노출되므로, 최소 컬럼의 읽기 전용 표로 둔다. 행 클릭은 주문 상세로만 이동한다.
 */
defineProps<{
  rows: AdminMemberActivityRow[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  loadError: string | null
  emptyMessage: string
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  open: [row: AdminMemberActivityRow]
  retry: []
}>()

const headers = [
  { title: '번호', key: 'rowNumber', sortable: false, width: 72 },
  { title: '주문번호', key: 'orderNo', sortable: false },
  { title: '상태', key: 'status', sortable: false },
  { title: '금액', key: 'amount', sortable: false, align: 'end' as const },
  { title: '일시', key: 'at', sortable: false },
]
</script>

<template>
  <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="member-activity-error">
    <div class="d-flex align-center justify-space-between flex-wrap ga-2">
      <span>{{ loadError }}</span>
      <v-btn size="small" variant="outlined" color="error" data-testid="member-activity-retry" @click="emit('retry')">다시 시도</v-btn>
    </div>
  </v-alert>
  <v-data-table-server
    v-else
    :headers="headers"
    :items="rows"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_MEMBER_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="key"
    hover
    class="adm-table adm-table--compact"
    data-testid="member-activity-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.rowNumber`]="{ index }">
      <span class="text-medium-emphasis">{{ totalCount - page * size - index }}</span>
    </template>
    <template #[`item.orderNo`]="{ item }">
      <a
        v-if="item.orderId"
        href="#"
        class="font-weight-medium text-primary text-decoration-none"
        data-testid="activity-order-no"
        @click.prevent="emit('open', item)"
      >{{ item.orderNo }}</a>
      <span v-else class="font-weight-medium" data-testid="activity-order-no">{{ item.orderNo }}</span>
      <div v-if="item.caption" class="text-caption text-medium-emphasis">{{ item.caption }}</div>
    </template>
    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(item.statusSemantic)" size="small" variant="flat" data-testid="activity-status-chip">
        {{ item.statusLabel }}
      </v-chip>
    </template>
    <template #[`item.amount`]="{ item }">
      {{ formatWon(item.amount) }}
    </template>
    <template #[`item.at`]="{ item }">
      {{ formatDateTime(item.at) }}
    </template>
    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="member-activity-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiReceiptTextOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-2 font-weight-medium mb-0">{{ emptyMessage }}</p>
      </div>
    </template>
  </v-data-table-server>
</template>
