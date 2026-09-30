<script setup lang="ts">
import { mdiMessageTextOutline } from '@mdi/js'
import type { AdminInquiryListItem } from '#layers/admin/app/types/admin-inquiry'
import { ADMIN_INQUIRY_PAGE_SIZES, inquiryExcerpt } from '#layers/admin/app/lib/admin-inquiry-query'
import { ADMIN_ORDERS_PATH } from '#layers/admin/app/lib/admin-back-path'
import { inquiryCategoryLabel } from '~/lib/constants/inquiry'
import { formatDateTime } from '~/lib/utils/datetime'

/**
 * 운영자 문의 목록 표(Track 106-4 · AdminProductQuestionTable 선례). 카테고리 · 문의 발췌 · 작성자(BE 마스킹 이메일 그대로) · 첨부 주문(관리자 주문
 * 상세 링크) · 답변 여부 · 작성일 · 답변하기/답변 수정 버튼.
 */
defineProps<{
  items: AdminInquiryListItem[]
  totalCount: number
  page: number
  size: number
  loading: boolean
  filtersActive: boolean
}>()
const emit = defineEmits<{
  answer: [item: AdminInquiryListItem]
  'update:page': [page: number]
  'update:size': [size: number]
  reset: []
}>()

const headers = [
  { title: '카테고리', key: 'category', sortable: false, width: 130 },
  { title: '문의', key: 'content', sortable: false },
  { title: '작성자', key: 'buyerEmailMasked', sortable: false, width: 180 },
  { title: '주문', key: 'orderNo', sortable: false, width: 170 },
  { title: '답변', key: 'answered', sortable: false, width: 100 },
  { title: '작성일', key: 'createdAt', sortable: false, width: 150 },
  { title: '', key: 'actions', sortable: false, width: 120, align: 'end' as const },
]
</script>

<template>
  <v-data-table-server
    :headers="headers"
    :items="items"
    :items-length="totalCount"
    :page="page + 1"
    :items-per-page="size"
    :items-per-page-options="ADMIN_INQUIRY_PAGE_SIZES.map((value) => ({ value, title: String(value) }))"
    :loading="loading"
    items-per-page-text="페이지당"
    page-text="{0}-{1} / {2}"
    loading-text="불러오는 중…"
    item-value="inquiryId"
    class="adm-table adm-table--compact"
    data-testid="admin-inquiry-table"
    @update:page="(next: number) => emit('update:page', next - 1)"
    @update:items-per-page="(next: number) => emit('update:size', next)"
  >
    <template #[`item.category`]="{ item }">
      <span data-testid="row-category">{{ inquiryCategoryLabel(item.category) }}</span>
    </template>
    <template #[`item.content`]="{ item }">
      <span :title="item.content" data-testid="row-content">{{ inquiryExcerpt(item.content) }}</span>
    </template>
    <template #[`item.buyerEmailMasked`]="{ item }">
      <span data-testid="row-buyer">{{ item.buyerEmailMasked ?? '—' }}</span>
    </template>
    <template #[`item.orderNo`]="{ item }">
      <NuxtLink v-if="item.orderId" :to="`${ADMIN_ORDERS_PATH}/${item.orderId}`" data-testid="row-order">{{ item.orderNo ?? item.orderId }}</NuxtLink>
      <span v-else>—</span>
    </template>
    <template #[`item.answered`]="{ item }">
      <span data-testid="row-answered">{{ item.answerContent !== undefined ? '답변완료' : '미답변' }}</span>
    </template>
    <template #[`item.createdAt`]="{ item }">
      {{ formatDateTime(item.createdAt) }}
    </template>
    <template #[`item.actions`]="{ item }">
      <v-btn
        size="small"
        :variant="item.answerContent !== undefined ? 'outlined' : 'flat'"
        color="primary"
        data-testid="row-answer"
        @click="emit('answer', item)"
      >
        {{ item.answerContent !== undefined ? '답변 수정' : '답변하기' }}
      </v-btn>
    </template>

    <template #no-data>
      <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-inquiry-empty">
        <v-avatar color="surface-variant" size="48" class="mb-3">
          <v-icon :icon="mdiMessageTextOutline" size="22" class="text-medium-emphasis" />
        </v-avatar>
        <p class="text-subtitle-2 font-weight-medium mb-1">조건에 맞는 문의가 없습니다</p>
        <v-btn v-if="filtersActive" size="small" variant="outlined" class="mt-2" @click="emit('reset')">필터 초기화</v-btn>
      </div>
    </template>
  </v-data-table-server>
</template>
