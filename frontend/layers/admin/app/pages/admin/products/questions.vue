<script setup lang="ts">
import { mdiAlertCircleOutline, mdiRefresh } from '@mdi/js'
import type {
  AdminProductQuestionListItem,
  AdminProductQuestionListQuery,
} from '#layers/admin/app/types/admin-product-question'
import { ADMIN_PRODUCT_QUESTION_STATUS_OPTIONS } from '#layers/admin/app/lib/constants/admin-product-question'
import {
  DEFAULT_ADMIN_PRODUCT_QUESTION_QUERY,
  hasActiveProductQuestionFilters,
  parseAdminProductQuestionQuery,
  toAdminProductQuestionRouteQuery,
} from '#layers/admin/app/lib/admin-product-question-query'
import { toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminProductQuestions } from '#layers/admin/app/composables/useAdminProductQuestions'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '상품 질문 · zslab-mall 관리자' })

/**
 * 상품 질문 관리(Track 106-2 · 리뷰 관리 화면 복제). URL query가 상태 필터·페이지의 단일 소스이고 정렬은 BE 고정(작성일 desc · 삭제 질문 제외).
 * 관리자는 숨김·해제만 한다 — 행 버튼 → 사유 다이얼로그 → 성공하면 목록을 다시 읽는다(PATCH 204 · 갱신 행 없음).
 */
const route = useRoute()
const router = useRouter()
const questionsApi = useAdminProductQuestions()

const query = computed<AdminProductQuestionListQuery>(() => parseAdminProductQuestionQuery(route.query))

const items = ref<AdminProductQuestionListItem[]>([])
const totalCount = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await questionsApi.list(query.value)
    if (sequence !== requestSequence) return
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

function applyQuery(patch: Partial<AdminProductQuestionListQuery>, resetPage = true): void {
  const next: AdminProductQuestionListQuery = { ...query.value, ...patch }
  if (resetPage && !('page' in patch)) next.page = 0
  void router.replace({ query: toAdminProductQuestionRouteQuery(next) })
}

function resetQuery(): void {
  void router.replace({ query: toAdminProductQuestionRouteQuery({ ...DEFAULT_ADMIN_PRODUCT_QUESTION_QUERY, size: query.value.size }) })
}

const filtersActive = computed(() => hasActiveProductQuestionFilters(query.value))

const statusTarget = ref<AdminProductQuestionListItem | null>(null)
function onChanged(): void {
  statusTarget.value = null
  void load()
}
</script>

<template>
  <div>
    <AdminPageHeader title="상품 질문" description="상품 Q&A 질문을 확인하고 부적절한 질문을 숨기거나 숨김을 해제합니다(사유 필수 · 감사 이력). 답변은 셀러가 합니다." />

    <v-card class="mb-4" data-testid="admin-question-filters">
      <v-card-text class="pa-4">
        <v-row dense align="center">
          <v-col cols="12" md="4">
            <v-select
              :model-value="query.status"
              :items="ADMIN_PRODUCT_QUESTION_STATUS_OPTIONS"
              label="상태"
              hide-details
              data-testid="filter-status"
              @update:model-value="(value) => applyQuery({ status: value })"
            />
          </v-col>
          <v-col cols="12" md="8" class="d-flex justify-end">
            <v-btn variant="text" :prepend-icon="mdiRefresh" data-testid="filter-reset" @click="resetQuery">초기화</v-btn>
          </v-col>
        </v-row>
      </v-card-text>
    </v-card>

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-question-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-question-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminProductQuestionTable
        v-else
        :items="items"
        :total-count="totalCount"
        :page="query.page"
        :size="query.size"
        :loading="loading"
        :filters-active="filtersActive"
        @change="(item) => (statusTarget = item)"
        @update:page="(page) => applyQuery({ page }, false)"
        @update:size="(size) => applyQuery({ size })"
        @reset="resetQuery"
      />
    </v-card>

    <AdminProductQuestionStatusDialog
      :open="statusTarget !== null"
      :question="statusTarget"
      @done="onChanged"
      @stale="onChanged"
      @cancel="statusTarget = null"
    />
  </div>
</template>
