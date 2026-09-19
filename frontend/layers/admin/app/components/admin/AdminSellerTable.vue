<script setup lang="ts">
import { mdiStoreOutline } from '@mdi/js'
import type { AdminSellerListItem } from '#layers/admin/app/types/admin-seller'
import { ADMIN_SELLER_PAGE_SIZES } from '#layers/admin/app/lib/constants/admin-seller'
import { sellerStatusChipClass, sellerStatusLabel, toSellerProductListPath } from '#layers/admin/app/lib/admin-seller-view'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 셀러 목록 표(FE-40·AdminOperatorTable 패턴). 행 클릭 → 상세, 상품 수 클릭 → 상품 목록 셀러 필터(0건은 비링크), 상태는 배지.
 * 계좌 등록 여부는 주 정산계좌 유무(BE hasPrimaryBankAccount).
 */
defineProps<{
  items: AdminSellerListItem[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  filtersActive: boolean
}>()
const emit = defineEmits<{ open: [item: AdminSellerListItem]; 'update:page': [page: number]; 'update:size': [size: number]; reset: [] }>()

const headers = [
  { title: '상호명', key: 'companyName', sortable: false },
  { title: '사업자번호', key: 'businessNo', sortable: false, width: 140 },
  { title: '대표자', key: 'ceoName', sortable: false, width: 110 },
  { title: '상태', key: 'status', sortable: false, width: 100 },
  { title: '상품 수', key: 'productCount', sortable: false, width: 90, align: 'end' as const },
  { title: '계좌', key: 'hasPrimaryBankAccount', sortable: false, width: 90 },
  { title: '등록일', key: 'createdAt', sortable: false, width: 150 },
]
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_SELLER_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="sellerPublicId"
    hover
    class="adm-table adm-table--compact"
    data-testid="admin-seller-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
    @click:row="(_event: Event, row: { item: AdminSellerListItem }) => emit('open', row.item)"
  >
    <template #[`item.companyName`]="{ item }">
      <div class="font-weight-medium" data-testid="row-company">{{ item.companyName }}</div>
      <div class="text-caption text-medium-emphasis">{{ item.contactEmail ?? '—' }}</div>
    </template>
    <template #[`item.businessNo`]="{ item }">
      <span data-testid="row-business-no">{{ item.businessNo ?? '—' }}</span>
    </template>
    <template #[`item.status`]="{ item }">
      <v-chip size="small" variant="flat" :class="sellerStatusChipClass(item.status)" data-testid="row-status">{{ sellerStatusLabel(item.status) }}</v-chip>
    </template>
    <template #[`item.productCount`]="{ item }">
      <NuxtLink
        v-if="item.productCount > 0"
        :to="toSellerProductListPath(item.sellerPublicId)"
        class="text-primary text-decoration-none"
        data-testid="row-product-count"
        @click.stop
      >{{ item.productCount }}</NuxtLink>
      <span v-else class="text-medium-emphasis" data-testid="row-product-count">0</span>
    </template>
    <template #[`item.hasPrimaryBankAccount`]="{ item }">
      <span :class="item.hasPrimaryBankAccount ? '' : 'text-warning'" data-testid="row-bank">{{ item.hasPrimaryBankAccount ? '등록' : '미등록' }}</span>
    </template>
    <template #[`item.createdAt`]="{ item }">
      {{ formatDateTime(item.createdAt) }}
    </template>

    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-seller-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiStoreOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <template v-if="filtersActive">
          <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 셀러가 없습니다</p>
          <p class="text-body-2 text-medium-emphasis mb-3">상태·검색어를 바꾸거나 초기화해 보세요.</p>
          <v-btn size="small" variant="outlined" @click="emit('reset')">필터 초기화</v-btn>
        </template>
        <template v-else>
          <p class="text-subtitle-2 font-weight-medium mb-1">셀러가 없습니다</p>
          <p class="text-body-2 text-medium-emphasis">입점 등록으로 첫 셀러를 추가하세요.</p>
        </template>
      </div>
    </template>
  </v-data-table-server>
</template>
