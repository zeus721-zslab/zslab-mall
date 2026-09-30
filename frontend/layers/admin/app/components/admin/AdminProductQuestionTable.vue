<script setup lang="ts">
import { mdiCommentQuestionOutline } from '@mdi/js'
import type { AdminProductQuestionListItem } from '#layers/admin/app/types/admin-product-question'
import {
  ADMIN_PRODUCT_QUESTION_PAGE_SIZES,
  ADMIN_PRODUCT_QUESTION_TRANSITION_LABEL,
} from '#layers/admin/app/lib/constants/admin-product-question'
import {
  productQuestionExcerpt,
  productQuestionStatusChipClass,
  productQuestionTransitionTarget,
} from '#layers/admin/app/lib/admin-product-question-query'
import { productQuestionStatusLabel } from '~/lib/constants/product-question'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 상품 질문 목록 표(Track 106-2 · AdminReviewTable 복제). 상품(삭제 상품은 대시) · 질문 발췌 · 답변 여부 · 상태 배지 · 숨김 사유 · 작성일 · 숨김/해제 버튼.
 */
defineProps<{
  items: AdminProductQuestionListItem[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  filtersActive: boolean
}>()
const emit = defineEmits<{
  change: [item: AdminProductQuestionListItem]
  'update:page': [page: number]
  'update:size': [size: number]
  reset: []
}>()

const headers = [
  { title: '상품', key: 'productName', sortable: false },
  { title: '질문', key: 'content', sortable: false },
  { title: '답변', key: 'answered', sortable: false, width: 100 },
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
    :items-per-page-options="ADMIN_PRODUCT_QUESTION_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="questionId"
    class="adm-table adm-table--compact"
    data-testid="admin-question-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.productName`]="{ item }">
      <div class="font-weight-medium" data-testid="row-product">{{ item.productName ?? '삭제된 상품' }}</div>
    </template>
    <template #[`item.content`]="{ item }">
      <span :title="item.content" data-testid="row-content">{{ productQuestionExcerpt(item.content) }}</span>
    </template>
    <template #[`item.answered`]="{ item }">
      <span data-testid="row-answered">{{ item.answerContent !== undefined ? '답변완료' : '미답변' }}</span>
    </template>
    <template #[`item.status`]="{ item }">
      <v-chip size="small" variant="flat" :class="productQuestionStatusChipClass(item.status)" data-testid="row-status">
        {{ productQuestionStatusLabel(item.status) }}
      </v-chip>
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
        {{ ADMIN_PRODUCT_QUESTION_TRANSITION_LABEL[productQuestionTransitionTarget(item.status)] }}
      </v-btn>
    </template>

    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-question-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiCommentQuestionOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <template v-if="filtersActive">
          <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 질문이 없습니다</p>
          <v-btn size="small" variant="outlined" class="mt-2" @click="emit('reset')">필터 초기화</v-btn>
        </template>
        <p v-else class="text-subtitle-2 font-weight-medium mb-1">질문이 없습니다</p>
      </div>
    </template>
  </v-data-table-server>
</template>
