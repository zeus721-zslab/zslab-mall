<script setup lang="ts">
import { mdiDotsVertical, mdiImageOffOutline, mdiPencilOutline } from '@mdi/js'
import type { SellerProductSummary } from '#layers/seller/app/types/seller-product'
import { formatDateTime } from '~/lib/utils/datetime'
import {
  SELLER_PRODUCT_PAGE_SIZES,
  SELLER_PRODUCT_STATUS_LABEL,
  SELLER_PRODUCT_STATUS_SEMANTIC,
  SELLER_SALE_ACTION_LABEL,
  SELLER_SALE_STOP_SOURCE_LABEL,
  type SellerSaleAction,
} from '#layers/seller/app/lib/constants/seller-product'
import { resolveSellerSaleAction } from '#layers/seller/app/lib/seller-product-sale-status'
import { formatWon } from '#layers/seller/app/lib/format'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'

// 상품 표(Track 90-C-3·v-data-table-server·관리자 AdminProductTable 복제·셀러/공급가/판매기간/품절 컬럼 없음). 페이지·크기는 부모(URL)가 소유하고
// 표는 이벤트만 올린다. 정렬은 필터 카드의 정렬 select가 담당하므로 컬럼 정렬은 끈다. 수정 버튼은 부모가 수정 화면(90-C-4)으로 이동시킨다.
// 행 메뉴(Track 96-5·D-206): SALE=판매중지 · STOPPED(셀러 중지)=재판매 · STOPPED(관리자 중지)=재판매 비활성+운영자 문의 · 그 외 상태는 메뉴 미노출.
// 비활성 항목은 @click 리스너가 루트 요소에 fallthrough 되어 프로그램 클릭·키보드로 발화할 수 있으므로 emit 앞에서 한 번 더 막는다(fail-closed·R2 Q10 실측).
defineProps<{
  items: SellerProductSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  edit: [item: SellerProductSummary]
  saleAction: [item: SellerProductSummary, action: SellerSaleAction]
}>()

function saleActionOf(item: SellerProductSummary) {
  return resolveSellerSaleAction(item.status, item.saleStopSource)
}

const headers = [
  { title: '', key: 'thumbnailUrl', sortable: false, width: 64 },
  { title: '상품명', key: 'name', sortable: false },
  { title: '카테고리', key: 'categoryName', sortable: false },
  { title: '상태', key: 'status', sortable: false },
  { title: '판매가', key: 'basePrice', sortable: false, align: 'end' as const },
  { title: '옵션수', key: 'variantCount', sortable: false, align: 'end' as const },
  { title: '등록일', key: 'createdAt', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

// 이미지 로드 실패한 행은 대체 아이콘으로 바꾼다(URL 유효성은 서버가 보장하지 않음).
const brokenThumbnails = ref<Set<string>>(new Set())
function markBroken(productPublicId: string): void {
  brokenThumbnails.value = new Set(brokenThumbnails.value).add(productPublicId)
}
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="SELLER_PRODUCT_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="productPublicId"
    hover
    class="slr-table slr-table--compact"
    data-testid="seller-product-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.thumbnailUrl`]="{ item }">
      <img
        v-if="item.thumbnailUrl && !brokenThumbnails.has(item.productPublicId)"
        :src="item.thumbnailUrl"
        :alt="item.name"
        class="slr-thumb"
        loading="lazy"
        @error="markBroken(item.productPublicId)"
      >
      <div v-else class="slr-thumb slr-thumb--placeholder" aria-label="이미지 없음">
        <v-icon :icon="mdiImageOffOutline" size="20" />
      </div>
    </template>

    <template #[`item.name`]="{ item }">
      <div class="slr-product-name font-weight-medium" :title="item.name" data-testid="row-product-name">{{ item.name }}</div>
      <div class="slr-product-id">{{ item.productPublicId }}</div>
    </template>

    <template #[`item.categoryName`]="{ item }">
      {{ item.categoryName ?? '—' }}
    </template>

    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(SELLER_PRODUCT_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="status-chip">
        {{ SELLER_PRODUCT_STATUS_LABEL[item.status] }}
      </v-chip>
      <div v-if="item.status === 'STOPPED' && item.saleStopSource" class="text-caption text-medium-emphasis mt-1" data-testid="stop-source">
        {{ SELLER_SALE_STOP_SOURCE_LABEL[item.saleStopSource] }}
      </div>
    </template>

    <template #[`item.basePrice`]="{ item }">
      <span data-testid="row-base-price">{{ formatWon(item.basePrice) }}</span>
    </template>

    <template #[`item.variantCount`]="{ item }">
      {{ item.variantCount.toLocaleString('ko-KR') }}
    </template>

    <template #[`item.createdAt`]="{ item }">
      <span class="text-body-2" data-testid="row-created-at">{{ formatDateTime(item.createdAt) }}</span>
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn
          :icon="mdiPencilOutline"
          size="small"
          variant="text"
          :aria-label="`${item.name} 수정`"
          data-testid="row-edit"
          @click="emit('edit', item)"
        />
        <v-menu v-if="saleActionOf(item).action">
          <template #activator="{ props: activatorProps }">
            <v-btn v-bind="activatorProps" :icon="mdiDotsVertical" size="small" variant="text" :aria-label="`${item.name} 판매 관리`" data-testid="row-menu" />
          </template>
          <v-list density="compact" min-width="220">
            <v-list-subheader>판매 관리</v-list-subheader>
            <v-list-item
              :title="SELLER_SALE_ACTION_LABEL[saleActionOf(item).action!]"
              :subtitle="saleActionOf(item).note ?? undefined"
              :disabled="saleActionOf(item).disabled"
              lines="two"
              data-testid="row-sale-action"
              @click="!saleActionOf(item).disabled && emit('saleAction', item, saleActionOf(item).action!)"
            />
          </v-list>
        </v-menu>
      </div>
    </template>

    <template #no-data>
      <slot name="empty" />
    </template>
  </v-data-table-server>
</template>
