<script setup lang="ts">
import { mdiCommentTextOutline, mdiStar } from '@mdi/js'
import type { AdminReviewListItem } from '#layers/admin/app/types/admin-review'
import { ADMIN_REVIEW_PAGE_SIZES, ADMIN_REVIEW_TRANSITION_LABEL } from '#layers/admin/app/lib/constants/admin-review'
import {
  reviewExcerpt,
  reviewPhotoText,
  reviewStatusChipClass,
  reviewStatusLabel,
  reviewTransitionTarget,
} from '#layers/admin/app/lib/admin-review-view'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 리뷰 목록 표(Track 106-1 · AdminSellerTable 패턴). 상품(삭제 상품은 대시) · 별점 · 본문 발췌 · 사진 수 · 상태 배지 · 숨김 사유 · 작성일 · 숨김/해제 버튼.
 * 사진은 이미지 대신 장수만 보인다 — 숨김 리뷰 사진은 관리자에게도 404라서(D-237 결정 4) "사진 N장(비공개)"로 적는다.
 */
defineProps<{
  items: AdminReviewListItem[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  filtersActive: boolean
}>()
const emit = defineEmits<{
  change: [item: AdminReviewListItem]
  'update:page': [page: number]
  'update:size': [size: number]
  reset: []
}>()

const headers = [
  { title: '상품', key: 'productName', sortable: false },
  { title: '별점', key: 'rating', sortable: false, width: 80 },
  { title: '본문', key: 'content', sortable: false },
  { title: '사진', key: 'photoUrls', sortable: false, width: 130 },
  { title: '상태', key: 'status', sortable: false, width: 90 },
  { title: '숨김 사유', key: 'hiddenReason', sortable: false, width: 180 },
  { title: '작성일', key: 'createdAt', sortable: false, width: 150 },
  { title: '', key: 'actions', sortable: false, width: 110, align: 'end' as const },
]
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_REVIEW_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="reviewId"
    class="adm-table adm-table--compact"
    data-testid="admin-review-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.productName`]="{ item }">
      <div class="font-weight-medium" data-testid="row-product">{{ item.productName ?? '삭제된 상품' }}</div>
      <div v-if="item.optionLabel" class="text-caption text-medium-emphasis">{{ item.optionLabel }}</div>
    </template>
    <template #[`item.rating`]="{ item }">
      <span class="d-inline-flex align-center ga-1" data-testid="row-rating">
        <v-icon :icon="mdiStar" size="16" color="warning" />{{ item.rating }}
      </span>
    </template>
    <template #[`item.content`]="{ item }">
      <span :title="item.content" data-testid="row-content">{{ reviewExcerpt(item.content) }}</span>
    </template>
    <template #[`item.photoUrls`]="{ item }">
      <span :class="item.status === 'HIDDEN' ? 'text-medium-emphasis' : ''" data-testid="row-photos">{{ reviewPhotoText(item) }}</span>
    </template>
    <template #[`item.status`]="{ item }">
      <v-chip size="small" variant="flat" :class="reviewStatusChipClass(item.status)" data-testid="row-status">{{ reviewStatusLabel(item.status) }}</v-chip>
    </template>
    <template #[`item.hiddenReason`]="{ item }">
      <span data-testid="row-hidden-reason">{{ item.hiddenReason ?? '—' }}</span>
    </template>
    <template #[`item.createdAt`]="{ item }">
      {{ formatDateTime(item.createdAt) }}
    </template>
    <template #[`item.actions`]="{ item }">
      <v-btn
        size="small"
        :variant="item.status === 'VISIBLE' ? 'outlined' : 'tonal'"
        :color="item.status === 'VISIBLE' ? 'error' : 'primary'"
        data-testid="row-change-status"
        @click="emit('change', item)"
      >
        {{ ADMIN_REVIEW_TRANSITION_LABEL[reviewTransitionTarget(item.status)] }}
      </v-btn>
    </template>

    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-review-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiCommentTextOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <template v-if="filtersActive">
          <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 리뷰가 없습니다</p>
          <v-btn size="small" variant="outlined" class="mt-2" @click="emit('reset')">필터 초기화</v-btn>
        </template>
        <p v-else class="text-subtitle-2 font-weight-medium mb-1">리뷰가 없습니다</p>
      </div>
    </template>
  </v-data-table-server>
</template>
