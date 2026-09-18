<script setup lang="ts">
import { mdiAlertCircleOutline, mdiPlus, mdiShapeOutline } from '@mdi/js'
import type { AdminCategorySummary } from '#layers/admin/app/types/admin-category'
import { moveCategory, toProductListPath } from '#layers/admin/app/lib/admin-category-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminCategories } from '#layers/admin/app/composables/useAdminCategories'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: '카테고리 · zslab-mall 관리자' })

// 관리자 카테고리 관리(FE-38·Track 89-C BE). 루트 카테고리 소량(6건)이라 필터·페이징 없이 전량 표시한다. 등록·수정은 다이얼로그,
// 삭제는 확인 다이얼로그(상품 0건만), 순서는 표의 위/아래 버튼 → PATCH /order 전체 배열. 모든 변경 후 목록을 재조회한다.
const categoriesApi = useAdminCategories()
const toast = useAdminToast()

// ---------- 목록 ----------
const items = ref<AdminCategorySummary[]>([])
const defaultCommissionRate = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await categoriesApi.list()
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response.items
    defaultCommissionRate.value = response.defaultCommissionRate
  } catch (error) {
    if (sequence !== requestSequence) return
    loadError.value = toAdminErrorMessage(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}
onMounted(() => { void load() })

// ---------- 등록·수정 ----------
const dialogOpen = ref(false)
const editing = ref<AdminCategorySummary | null>(null)

function openCreate(): void {
  editing.value = null
  dialogOpen.value = true
}

function openEdit(item: AdminCategorySummary): void {
  editing.value = item
  dialogOpen.value = true
}

function closeDialog(refresh: boolean): void {
  dialogOpen.value = false
  editing.value = null
  if (refresh) void load()
}

// ---------- 삭제 ----------
const deleteTarget = ref<AdminCategorySummary | null>(null)
const deleting = ref(false)

const deleteMessage = computed(() =>
  deleteTarget.value
    ? `"${deleteTarget.value.displayName}" 카테고리를 삭제합니다.\n상품 등록 드롭다운과 카탈로그 탭에서 즉시 사라지며, 같은 이름으로 다시 등록할 수 있습니다.`
    : '',
)

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true
  try {
    await categoriesApi.remove(deleteTarget.value.categoryId)
    toast.danger(`"${deleteTarget.value.displayName}" 카테고리를 삭제했습니다.`)
  } catch (error) {
    const code = extractErrorCode(error)
    // 409(그 사이 상품이 연결됨)·404(이미 삭제)는 최신 목록으로 되돌린다.
    if (code === 'CATEGORY_HAS_PRODUCTS' || code === 'CATEGORY_NOT_FOUND') toast.warning(toAdminErrorMessage(error))
    else toast.danger(toAdminErrorMessage(error))
  } finally {
    deleting.value = false
    deleteTarget.value = null
    void load()
  }
}

// ---------- 정렬 ----------
const reordering = ref(false)

async function move(index: number, direction: -1 | 1): Promise<void> {
  if (reordering.value) return
  const next = moveCategory(items.value.map((item) => item.categoryId), index, direction)
  if (!next) return
  reordering.value = true
  try {
    await categoriesApi.reorder({ categoryIds: next })
  } catch (error) {
    // 400(그 사이 목록이 바뀜)은 재조회로 정합을 맞춘다.
    toast.warning(toAdminErrorMessage(error))
  } finally {
    reordering.value = false
    await load()
  }
}

// ---------- 이동 ----------
function openProducts(item: AdminCategorySummary): void {
  void navigateTo(toProductListPath(item.categoryId))
}
</script>

<template>
  <div>
    <AdminPageHeader title="카테고리 관리" description="루트 카테고리의 이름·노출 순서·수수료율을 관리합니다. 수수료율은 셀러 개별율이 없을 때 적용되며, 변경 이후 생성되는 주문부터 반영됩니다.">
      <template #actions>
        <v-btn color="primary" :prepend-icon="mdiPlus" data-testid="go-create" @click="openCreate">카테고리 등록</v-btn>
      </template>
    </AdminPageHeader>

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-category-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-category-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminCategoryTable
        v-else
        :items="items"
        :default-commission-rate="defaultCommissionRate"
        :loading="loading"
        :reordering="reordering"
        @move="move"
        @edit="openEdit"
        @remove="(item) => { deleteTarget = item }"
        @open-products="openProducts"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-category-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiShapeOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <p class="text-subtitle-2 font-weight-medium mb-1">카테고리가 없습니다</p>
            <p class="text-body-2 text-medium-emphasis mb-3">상품을 등록하려면 카테고리가 먼저 필요합니다.</p>
            <v-btn size="small" variant="outlined" @click="openCreate">카테고리 등록</v-btn>
          </div>
        </template>
      </AdminCategoryTable>
    </v-card>

    <AdminCategoryEditDialog
      :open="dialogOpen"
      :item="editing"
      :default-commission-rate="defaultCommissionRate"
      :next-sort-order="items.length"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
    <AdminConfirmDialog
      :open="deleteTarget !== null"
      title="카테고리 삭제"
      :message="deleteMessage"
      confirm-label="삭제"
      confirm-color="error"
      :loading="deleting"
      test-id="admin-category-delete-dialog"
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
