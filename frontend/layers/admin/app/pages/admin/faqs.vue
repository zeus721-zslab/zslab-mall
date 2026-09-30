<script setup lang="ts">
import { mdiAlertCircleOutline, mdiHelpCircleOutline, mdiPlus } from '@mdi/js'
import type { AdminFaqItem } from '#layers/admin/app/types/admin-faq'
import { faqsInCategory } from '#layers/admin/app/lib/admin-faq-view'
import { moveCategory } from '#layers/admin/app/lib/admin-category-view'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import { useAdminFaqs } from '#layers/admin/app/composables/useAdminFaqs'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { FAQ_CATEGORIES, type FaqCategory, faqCategoryLabel } from '~/lib/constants/faq'

definePageMeta({ layout: 'admin', middleware: ['admin', 'vuetify'] })
useSeoMeta({ title: 'FAQ 관리 · zslab-mall 관리자' })

// 관리자 FAQ 관리(Track 106-3 · 카테고리 관리 화면 선례). 전체를 한 번 받아 카테고리 칩으로 나눠 보여 준다(수십~수백 건 · 페이징 없음).
// 등록·수정은 다이얼로그, 삭제는 확인 다이얼로그, 순서는 카테고리 안 위/아래 버튼 → PATCH /order(그 카테고리 전체 id). 모든 변경 후 재조회한다.
const faqsApi = useAdminFaqs()
const toast = useAdminToast()

const selectedCategory = ref<FaqCategory>('ORDER_PAYMENT')

// ---------- 목록 ----------
const items = ref<AdminFaqItem[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
let requestSequence = 0

const categoryItems = computed(() => faqsInCategory(items.value, selectedCategory.value))

function countOf(category: FaqCategory): number {
  return items.value.filter((item) => item.category === category).length
}

async function load(): Promise<void> {
  const sequence = ++requestSequence
  loading.value = true
  loadError.value = null
  try {
    const response = await faqsApi.list()
    if (sequence !== requestSequence) return // 늦게 도착한 이전 요청은 버린다
    items.value = response
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
const editing = ref<AdminFaqItem | null>(null)

function openCreate(): void {
  editing.value = null
  dialogOpen.value = true
}

function openEdit(item: AdminFaqItem): void {
  editing.value = item
  dialogOpen.value = true
}

function closeDialog(refresh: boolean): void {
  dialogOpen.value = false
  editing.value = null
  if (refresh) void load()
}

// ---------- 삭제 ----------
const deleteTarget = ref<AdminFaqItem | null>(null)
const deleting = ref(false)
const deleteMessage = computed(() =>
  deleteTarget.value ? `"${deleteTarget.value.question}" FAQ를 삭제합니다. 구매자 도우미에서 바로 사라지며 되돌릴 수 없습니다.` : '',
)

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value || deleting.value) return
  deleting.value = true
  try {
    await faqsApi.remove(deleteTarget.value.id)
    toast.danger('FAQ를 삭제했습니다.')
  } catch (error) {
    // 404(이미 삭제)는 최신 목록으로 되돌린다.
    if (extractErrorCode(error) === 'FAQ_NOT_FOUND') toast.warning(toAdminErrorMessage(error))
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
  const next = moveCategory(categoryItems.value.map((item) => item.id), index, direction)
  if (!next) return
  reordering.value = true
  try {
    await faqsApi.reorder({ category: selectedCategory.value, faqIds: next })
  } catch (error) {
    // 400(그 사이 목록이 바뀜)은 재조회로 정합을 맞춘다.
    toast.warning(toAdminErrorMessage(error))
  } finally {
    reordering.value = false
    await load()
  }
}
</script>

<template>
  <div>
    <AdminPageHeader title="FAQ 관리" description="구매자 화면 채팅 도우미에 보이는 자주 묻는 질문을 관리합니다. 순서는 카테고리 안에서 위/아래로 바꾸고, 숨김으로 바꾸면 도우미에서 바로 빠집니다.">
      <template #actions>
        <v-btn color="primary" :prepend-icon="mdiPlus" data-testid="go-create" @click="openCreate">FAQ 등록</v-btn>
      </template>
    </AdminPageHeader>

    <v-chip-group v-model="selectedCategory" mandatory selected-class="text-primary" class="mb-3" data-testid="admin-faq-categories">
      <v-chip
        v-for="category in FAQ_CATEGORIES"
        :key="category"
        :value="category"
        filter
        variant="outlined"
        :data-category="category"
        data-testid="admin-faq-category"
      >
        {{ faqCategoryLabel(category) }} {{ countOf(category) }}
      </v-chip>
    </v-chip-group>

    <v-card>
      <v-alert v-if="loadError" type="error" class="ma-4" :icon="mdiAlertCircleOutline" data-testid="admin-faq-error">
        <div class="d-flex align-center justify-space-between flex-wrap ga-2">
          <span>{{ loadError }}</span>
          <v-btn size="small" variant="outlined" color="error" data-testid="admin-faq-retry" @click="load">다시 시도</v-btn>
        </div>
      </v-alert>
      <AdminFaqTable
        v-else
        :items="categoryItems"
        :loading="loading"
        :reordering="reordering"
        @move="move"
        @edit="openEdit"
        @remove="(item) => { deleteTarget = item }"
      >
        <template #empty>
          <div class="d-flex flex-column align-center text-center py-10" data-testid="admin-faq-empty">
            <v-avatar color="surface-variant" size="48" class="mb-3">
              <v-icon :icon="mdiHelpCircleOutline" size="22" class="text-medium-emphasis" />
            </v-avatar>
            <p class="text-subtitle-2 font-weight-medium mb-1">이 카테고리에 FAQ가 없습니다</p>
            <v-btn size="small" variant="outlined" class="mt-2" @click="openCreate">FAQ 등록</v-btn>
          </div>
        </template>
      </AdminFaqTable>
    </v-card>

    <AdminFaqEditDialog
      :open="dialogOpen"
      :item="editing"
      :default-category="selectedCategory"
      @done="closeDialog(true)"
      @stale="closeDialog(true)"
      @cancel="closeDialog(false)"
    />
    <AdminConfirmDialog
      :open="deleteTarget !== null"
      title="FAQ 삭제"
      :message="deleteMessage"
      confirm-label="삭제"
      risk
      :loading="deleting"
      test-id="admin-faq-delete-dialog"
      @confirm="confirmDelete"
      @cancel="deleteTarget = null"
    />
  </div>
</template>
