<script setup lang="ts">
import { mdiAlertCircleOutline } from '@mdi/js'
import type { AdminInquiryListItem, AdminInquiryListQuery } from '#layers/admin/app/types/admin-inquiry'
import {
  DEFAULT_ADMIN_INQUIRY_QUERY,
  hasActiveInquiryFilters,
  parseAdminInquiryQuery,
  toAdminInquiryRouteQuery,
} from '#layers/admin/app/lib/admin-inquiry-query'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminInquiries } from '#layers/admin/app/composables/useAdminInquiries'
import {
  INQUIRY_ANSWERED_FILTERS,
  INQUIRY_ANSWERED_LABELS,
  INQUIRY_CATEGORIES,
  type InquiryAnsweredFilter,
  type InquiryCategory,
  inquiryCategoryLabel,
} from '~/lib/constants/inquiry'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '문의 관리 · zslab-mall 관리자' })

/**
 * 운영자 문의 관리(Track 106-4 · 상품 질문 관리 화면 선례). URL query가 답변 여부·카테고리·페이지의 단일 소스이고 정렬은 BE 고정(오래된 순 ·
 * 삭제 문의 제외). 행 버튼 → 답변 다이얼로그(등록·수정 겸용) → 성공·stale이면 목록을 다시 읽는다(PUT 204 · 갱신 행 없음).
 */
const route = useRoute()
const router = useRouter()
const inquiriesApi = useAdminInquiries()

const query = computed<AdminInquiryListQuery>(() => parseAdminInquiryQuery(route.query))

const items = ref<AdminInquiryListItem[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await inquiriesApi.list(query.value)
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    totalCount.value = response.totalCount
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

watch(() => route.query, () => void load(), { immediate: true, deep: true })

function applyQuery(patch: Partial<AdminInquiryListQuery>, resetPage = true): void {
  const next: AdminInquiryListQuery = { ...query.value, ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  void router.replace({ query: toAdminInquiryRouteQuery(next) })
}

function resetQuery(): void {
  void router.replace({ query: toAdminInquiryRouteQuery({ ...DEFAULT_ADMIN_INQUIRY_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveInquiryFilters(query.value))

const answerTarget = ref<AdminInquiryListItem | null>(null)
function onAnswered(): void {
  answerTarget.value = null
  void load()
}
</script>

<template>
  <div>
    <AdminPageHeader title="문의 관리" description="구매자가 운영자에게 남긴 1:1 문의에 답변합니다. 오래된 문의부터 보이며, 답변을 수정하면 구매자에게 새 답변으로 표시됩니다." />

    <v-card class="mb-4" data-testid="admin-inquiry-filters">
      <v-card-text class="pa-4">
        <v-btn-toggle
          :model-value="query.answered"
          mandatory
          density="comfortable"
          variant="outlined"
          color="primary"
          class="mb-3"
          data-testid="filter-answered"
          @update:model-value="(value: InquiryAnsweredFilter) => applyQuery({ answered: value })"
        >
          <v-btn v-for="filter in INQUIRY_ANSWERED_FILTERS" :key="filter" :value="filter" :data-answered="filter">{{ INQUIRY_ANSWERED_LABELS[filter] }}</v-btn>
        </v-btn-toggle>
        <v-chip-group
          :model-value="query.category"
          selected-class="text-primary"
          data-testid="filter-category"
          @update:model-value="(value: InquiryCategory | null | undefined) => applyQuery({ category: value ?? null })"
        >
          <v-chip
            v-for="category in INQUIRY_CATEGORIES"
            :key="category"
            :value="category"
            filter
            variant="outlined"
            :data-category="category"
            data-testid="filter-category-chip"
          >
            {{ inquiryCategoryLabel(category) }}
          </v-chip>
        </v-chip-group>
      </v-card-text>
    </v-card>

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-inquiry-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-inquiry-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminInquiryTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :filters-active="filtersActive"
        @answer="(item) => (answerTarget = item)"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @reset="resetQuery"
      />
    </v-card>

    <AdminInquiryAnswerDialog
      :open="answerTarget !== null"
      :item="answerTarget"
      @done="onAnswered"
      @stale="onAnswered"
      @cancel="answerTarget = null"
    />
  </div>
</template>
