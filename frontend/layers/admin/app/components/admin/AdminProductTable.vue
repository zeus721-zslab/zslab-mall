<script setup lang="ts">
import { mdiDotsVertical, mdiImageOffOutline, mdiPencilOutline, mdiTrashCanOutline } from '@mdi/js'
import type { AdminProductSummary } from '#layers/admin/app/types/admin-product'
import {
  ADMIN_PRODUCT_PAGE_SIZES,
  ADMIN_PRODUCT_STATUS_LABEL,
  ADMIN_SALE_STOP_SOURCE_LABEL,
  ADMIN_PRODUCT_STATUS_SEMANTIC,
  ADMIN_PRODUCT_STATUS_TARGETS,
  type AdminProductStatusTarget,
} from '#layers/admin/app/lib/constants/product'
import { formatSalePeriod, formatWon } from '#layers/admin/app/lib/format'
import { soldOutLabel, statusTargetTitle, statusTargetsFor } from '#layers/admin/app/lib/admin-product-view'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'

// 상품 표(FE-25·v-data-table-server). 페이지·크기는 부모(URL)가 소유하고 표는 이벤트만 올린다. 정렬은 필터 카드의 정렬 select가
// 담당하므로 컬럼 정렬은 끈다. 행 단위 처리 중(pendingIds)에는 해당 행의 토글·메뉴를 비활성해 중복 클릭을 막는다.
const props = defineProps<{
  items: AdminProductSummary[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  pendingIds: Set<string>
}>()

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
  toggleSoldOut: [item: AdminProductSummary, soldOut: boolean]
  changeStatus: [item: AdminProductSummary, target: AdminProductStatusTarget]
  /** 거부 철회(REJECTED → PENDING·사유 다이얼로그는 부모가 연다·Track 101-A). */
  withdrawRejection: [item: AdminProductSummary]
  edit: [item: AdminProductSummary]
  remove: [item: AdminProductSummary]
}>()

const selected = defineModel<string[]>('selected', { required: true })

const headers = [
  { title: '', key: 'thumbnailUrl', sortable: false, width: 64 },
  { title: '상품명', key: 'name', sortable: false },
  { title: '셀러', key: 'sellerName', sortable: false },
  { title: '재고', key: 'stockTotal', sortable: false, align: 'end' as const },
  { title: '상태', key: 'status', sortable: false },
  { title: '품절', key: 'soldOut', sortable: false },
  { title: '판매가', key: 'basePrice', sortable: false, align: 'end' as const },
  { title: '공급가(참고)', key: 'supplyPrice', sortable: false, align: 'end' as const },
  { title: '판매기간', key: 'salePeriod', sortable: false },
  { title: '수동 품절', key: 'soldOutManual', sortable: false },
  { title: '관리', key: 'actions', sortable: false, align: 'end' as const },
]

// 이미지 로드 실패한 행은 대체 아이콘으로 바꾼다(URL 유효성은 서버가 보장하지 않음).
const brokenThumbnails = ref<Set<string>>(new Set())
function markBroken(productPublicId: string): void {
  brokenThumbnails.value = new Set(brokenThumbnails.value).add(productPublicId)
}

// D-206 보정: 셀러 중지 상품은 STOPPED 목표가 "관리자 중지로 전환"으로 열린다(순수 함수·vitest).
function allowedTargets(item: AdminProductSummary): AdminProductStatusTarget[] {
  return statusTargetsFor(item)
}

// FE-58: :disabled와 같은 값을 핸들러가 재검사한다 — Vuetify VListItem은 disabled여도 click을 emit한다(프로그래밍 클릭 fallthrough).
function statusTargetDisabled(item: AdminProductSummary, target: AdminProductStatusTarget): boolean {
  return !allowedTargets(item).includes(target)
}

function requestStatusChange(item: AdminProductSummary, target: AdminProductStatusTarget): void {
  if (statusTargetDisabled(item, target)) return
  emit('changeStatus', item, target)
}

function isPending(item: AdminProductSummary): boolean {
  return props.pendingIds.has(item.productPublicId)
}

</script>

<template>
  <v-data-table-server
    v-model="selected"
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_PRODUCT_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="productPublicId"
    show-select
    hover
    class="adm-table"
    data-testid="admin-product-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.thumbnailUrl`]="{ item }">
      <img
        v-if="item.thumbnailUrl && !brokenThumbnails.has(item.productPublicId)"
        :src="item.thumbnailUrl"
        :alt="item.name"
        class="adm-thumb"
        loading="lazy"
        @error="markBroken(item.productPublicId)"
      >
      <div v-else class="adm-thumb adm-thumb--placeholder" aria-label="이미지 없음">
        <v-icon :icon="mdiImageOffOutline" size="20" />
      </div>
    </template>

    <template #[`item.name`]="{ item }">
      <div class="adm-product-name font-weight-medium" :title="item.name">{{ item.name }}</div>
      <div class="adm-product-id">{{ item.productPublicId }}</div>
    </template>

    <template #[`item.sellerName`]="{ item }">
      {{ item.sellerName ?? '—' }}
    </template>

    <template #[`item.stockTotal`]="{ item }">
      {{ item.stockTotal.toLocaleString('ko-KR') }}
    </template>

    <template #[`item.status`]="{ item }">
      <v-chip :class="semanticChipClass(ADMIN_PRODUCT_STATUS_SEMANTIC[item.status])" size="small" variant="flat" data-testid="status-chip">
        {{ ADMIN_PRODUCT_STATUS_LABEL[item.status] }}
      </v-chip>
      <div v-if="item.status === 'STOPPED' && item.saleStopSource" class="text-caption text-medium-emphasis mt-1" data-testid="stop-source">
        {{ ADMIN_SALE_STOP_SOURCE_LABEL[item.saleStopSource] }}
      </div>
    </template>

    <template #[`item.soldOut`]="{ item }">
      <v-chip :class="semanticChipClass(soldOutLabel(item).semantic)" size="small" variant="flat" data-testid="soldout-chip">
        {{ soldOutLabel(item).text }}
      </v-chip>
    </template>

    <template #[`item.basePrice`]="{ item }">
      {{ formatWon(item.basePrice) }}
    </template>

    <template #[`item.supplyPrice`]="{ item }">
      <span class="text-medium-emphasis">{{ formatWon(item.supplyPrice) }}</span>
    </template>

    <template #[`item.salePeriod`]="{ item }">
      <span class="text-body-2">{{ formatSalePeriod(item.saleStartAt, item.saleEndAt) }}</span>
    </template>

    <template #[`item.soldOutManual`]="{ item }">
      <v-switch
        :model-value="item.soldOutManual"
        :disabled="isPending(item)"
        :loading="isPending(item)"
        color="error"
        density="compact"
        hide-details
        inset
        :aria-label="`${item.name} 수동 품절`"
        data-testid="soldout-toggle"
        @update:model-value="(value) => emit('toggleSoldOut', item, Boolean(value))"
      />
    </template>

    <template #[`item.actions`]="{ item }">
      <div class="d-flex align-center justify-end ga-1">
        <v-btn :icon="mdiPencilOutline" size="small" variant="text" aria-label="수정" data-testid="row-edit" @click="emit('edit', item)" />
        <v-menu>
          <template #activator="{ props: activatorProps }">
            <v-btn
              v-bind="activatorProps"
              :icon="mdiDotsVertical"
              size="small"
              variant="text"
              :disabled="isPending(item)"
              aria-label="관리 메뉴"
              data-testid="row-menu"
            />
          </template>
          <v-list density="compact" min-width="180">
            <v-list-subheader>상태 전환</v-list-subheader>
            <v-list-item
              v-for="target in ADMIN_PRODUCT_STATUS_TARGETS"
              :key="target.value"
              :title="statusTargetTitle(item, target.value)"
              :disabled="statusTargetDisabled(item, target.value)"
              :data-testid="`row-status-${target.value}`"
              @click="requestStatusChange(item, target.value)"
            />
            <v-list-item
              v-if="item.status === 'REJECTED'"
              title="거부 철회"
              data-testid="row-withdraw-rejection"
              @click="emit('withdrawRejection', item)"
            />
            <v-divider class="my-1" />
            <v-list-item
              title="삭제"
              :prepend-icon="mdiTrashCanOutline"
              base-color="error"
              data-testid="row-delete"
              @click="emit('remove', item)"
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
