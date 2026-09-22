<script setup lang="ts">
import { mdiAlertCircleOutline, mdiArrowLeft, mdiContentSave, mdiDotsVertical } from '@mdi/js'
import type { ProductForm, ProductFormErrors } from '#layers/admin/app/types/admin-product-form'
import type { AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'
import type { AdminProductStatusTarget } from '#layers/admin/app/lib/constants/product'
import {
  ADMIN_PRODUCT_STATUS_LABEL,
  ADMIN_SALE_STOP_SOURCE_LABEL,
  ADMIN_PRODUCT_STATUS_SEMANTIC,
  ADMIN_PRODUCT_STATUS_TARGETS,
} from '#layers/admin/app/lib/constants/product'
import { semanticChipClass } from '#layers/admin/app/lib/constants/semantic'
import { formSnapshot, mapFieldErrors, validateForm } from '#layers/admin/app/lib/admin-product-form'
import { SAVE_STEP_LABEL, SaveStepError, saveProduct, type SaveStep } from '#layers/admin/app/lib/admin-product-save'
import { extractErrorCode, toAdminErrorMessage } from '#layers/admin/app/lib/admin-error-message'
import {
  ESCALATE_STOP_TITLE,
  escalateConfirmMessage,
  isEscalation,
  saleStopSourceAfterAdminChange,
  soldOutToggleSemantic,
  statusTargetTitle,
  statusTargetsFor,
} from '#layers/admin/app/lib/admin-product-view'
import { useAdminProducts } from '#layers/admin/app/composables/useAdminProducts'
import { useAdminToast } from '#layers/admin/app/composables/useAdminToast'
import { priceChangeMessage, priceChangeOf, priceChangeWarnings, type PriceChange } from '~/lib/utils/price-change'

// 상품 폼 셸(FE-26·신규/수정 공용). 섹션 3개(기본·이미지·옵션)를 조립하고 검증→저장 오케스트레이션→토스트→복귀를 담당한다.
// dirty = 저장 시점 스냅샷과 현재 스냅샷 비교. 라우트 이탈(onBeforeRouteLeave)과 새로고침(beforeunload) 모두 경고한다.
const props = defineProps<{
  mode: 'create' | 'edit'
  initial: ProductForm
  sellers: AdminSellerSummary[]
  categories: CategorySummary[]
  sellerName?: string
  /** 목록 복귀 경로(?back=·검증된 값). */
  backPath: string
}>()

const emit = defineEmits<{
  /** 등록 후속 단계 실패 → 이미 생성된 상품의 수정 화면으로 전환(중복 등록 방지). */
  createdPartially: [productPublicId: string]
  /** 수정 모드에서 상태 전환·수동 품절 등 서버 상태가 바뀌어 상단 표시를 갱신해야 할 때. */
  statusChanged: []
}>()

const productsApi = useAdminProducts()
const toast = useAdminToast()
const router = useRouter()

const form = ref<ProductForm>(structuredClone(toRaw(props.initial)))
const errors = ref<ProductFormErrors>({})
const saving = ref(false)
const uploading = ref({ GALLERY: false, DETAIL: false })
const failedStep = ref<SaveStep | null>(null)
const failedMessage = ref('')
let savedSnapshot = formSnapshot(form.value)
// 직전 저장 시점의 판매가(FE-61). 저장에 성공할 때마다 갱신해 "이번 저장에서 바뀌는 금액"만 확인받는다.
let savedBasePrice = form.value.basePrice

// 확인 대기 중인 판매가 변동(null이면 다이얼로그 닫힘).
const priceChange = ref<PriceChange | null>(null)

const dirty = computed(() => formSnapshot(form.value) !== savedSnapshot)
const anyUploading = computed(() => uploading.value.GALLERY || uploading.value.DETAIL)
const canSave = computed(() => !saving.value && !anyUploading.value)

// ---------- 이탈 경고 ----------
onBeforeRouteLeave(() => {
  if (!dirty.value || saving.value) return true
  return window.confirm('저장하지 않은 변경 사항이 있습니다. 이동하시겠습니까?')
})
function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (!dirty.value) return
  event.preventDefault()
}
onMounted(() => window.addEventListener('beforeunload', onBeforeUnload))
onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))

// ---------- 저장 ----------
/**
 * 저장 요청. 검증을 마친 뒤, 수정 모드에서 판매가가 바뀌었으면 확인 다이얼로그를 열고 멈춘다(FE-61).
 * 가격이 그대로거나 신규 등록이면 바로 저장한다.
 */
async function save(): Promise<void> {
  if (!canSave.value) return
  errors.value = validateForm(form.value, props.mode)
  if (Object.keys(errors.value).length > 0) {
    toast.warning('입력값을 확인해 주세요.')
    return
  }
  const change = props.mode === 'edit' ? priceChangeOf(savedBasePrice, form.value.basePrice) : null
  if (change) {
    priceChange.value = change
    return
  }
  await runSave()
}

/** 판매가 확인 후 이어서 저장한다. */
async function confirmPriceChange(): Promise<void> {
  priceChange.value = null
  await runSave()
}

async function runSave(): Promise<void> {
  saving.value = true
  failedStep.value = null
  failedMessage.value = ''
  try {
    await saveProduct(productsApi, form.value, props.mode)
    savedSnapshot = formSnapshot(form.value)
    savedBasePrice = form.value.basePrice
    toast.success(props.mode === 'create' ? '상품을 등록했습니다.' : '상품을 저장했습니다.')
    await router.push(props.backPath)
    return
  } catch (error) {
    if (error instanceof SaveStepError) {
      failedStep.value = error.step
      failedMessage.value = toAdminErrorMessage(error.failure)
      const fieldErrors = (error.failure as { data?: { fieldErrors?: { field: string; message: string }[] } } | null)?.data?.fieldErrors
      errors.value = mapFieldErrors(fieldErrors)
      if (extractErrorCode(error.failure) === 'PRODUCT_VARIANT_OPTION_CONFLICT') {
        toast.warning('삭제된 조합은 다시 만들 수 없습니다. 같은 옵션 조합이 이미 있거나 삭제 이력이 있습니다.')
      } else {
        toast.danger(`${SAVE_STEP_LABEL[error.step]} 실패 — ${failedMessage.value}`)
      }
      if (props.mode === 'create' && error.productPublicId) {
        // 상품은 생성됨 → 수정 화면으로 전환해 남은 단계(이미지·옵션)를 재시도한다. 스냅샷을 갱신해 이탈 경고 없이 넘어간다.
        savedSnapshot = formSnapshot(form.value)
        emit('createdPartially', error.productPublicId)
      }
    } else {
      toast.danger(toAdminErrorMessage(error))
    }
  } finally {
    saving.value = false
  }
}

// ---------- 수정 모드: 상태 전환·수동 품절(즉시 반영·폼 저장과 무관) ----------
const statusBusy = ref(false)
// D-206 보정: 셀러 중지 상품은 STOPPED 목표가 "관리자 중지로 전환"(확인 다이얼로그 경유·status 유지·주체만 ADMIN).
const saleState = computed(() => ({ status: form.value.status ?? 'DRAFT', saleStopSource: form.value.saleStopSource }))
const allowedTargets = computed(() => (form.value.status ? statusTargetsFor(saleState.value) : []))
const escalateOpen = ref(false)

// FE-58: :disabled와 같은 값을 핸들러가 재검사한다 — Vuetify VListItem은 disabled여도 click을 emit한다(프로그래밍 클릭 fallthrough).
function statusTargetDisabled(target: AdminProductStatusTarget): boolean {
  return !allowedTargets.value.includes(target)
}

function requestStatusChange(target: AdminProductStatusTarget): void {
  if (statusTargetDisabled(target)) return
  if (isEscalation(saleState.value, target)) {
    escalateOpen.value = true
    return
  }
  void changeStatus(target)
}

async function confirmEscalate(): Promise<void> {
  escalateOpen.value = false
  await changeStatus('STOPPED')
}

async function changeStatus(target: AdminProductStatusTarget): Promise<void> {
  if (!form.value.productPublicId || !form.value.status || statusBusy.value) return
  statusBusy.value = true
  const escalation = isEscalation(saleState.value, target)
  try {
    const response = await productsApi.changeStatus(form.value.productPublicId, form.value.status, target)
    form.value.status = response.status
    // D-206: 관리자가 STOPPED로 바꾸면 주체는 항상 ADMIN(BE ProductSaleStatusService 기록값과 동일·응답에는 없음), SALE 복귀면 null.
    form.value.saleStopSource = saleStopSourceAfterAdminChange(response.status) ?? null
    refreshSnapshotStatus()
    toast.info(escalation ? `상태 → ${ESCALATE_STOP_TITLE}` : `상태 → ${ADMIN_PRODUCT_STATUS_LABEL[response.status]}`)
    emit('statusChanged')
  } catch (error) {
    toast.danger(toAdminErrorMessage(error))
  } finally {
    statusBusy.value = false
  }
}

async function toggleSoldOut(value: boolean): Promise<void> {
  if (!form.value.productPublicId || statusBusy.value) return
  const before = form.value.soldOutManual
  form.value.soldOutManual = value
  statusBusy.value = true
  try {
    await productsApi.setSoldOut(form.value.productPublicId, value)
    refreshSnapshotStatus()
    toast.show(soldOutToggleSemantic(value), `수동 품절을 ${value ? '켰' : '껐'}습니다.`)
  } catch (error) {
    form.value.soldOutManual = before
    toast.danger(toAdminErrorMessage(error))
  } finally {
    statusBusy.value = false
  }
}

/** 서버 상태 필드(status·soldOutManual)는 폼 저장 대상이 아니므로 스냅샷의 해당 값만 현재값으로 맞춰 dirty에서 제외한다. */
function refreshSnapshotStatus(): void {
  const parsed = JSON.parse(savedSnapshot) as Record<string, unknown>
  parsed.status = form.value.status
  parsed.saleStopSource = form.value.saleStopSource
  parsed.soldOutManual = form.value.soldOutManual
  savedSnapshot = JSON.stringify(parsed)
}

defineExpose({ form, dirty })
</script>

<template>
  <div data-testid="admin-product-form">
    <v-card v-if="mode === 'edit' && form.status" class="mb-4" data-testid="section-status">
      <v-card-text class="d-flex align-center flex-wrap ga-4 py-3 px-5">
        <span class="text-body-2 text-medium-emphasis">상태</span>
        <v-chip :class="semanticChipClass(ADMIN_PRODUCT_STATUS_SEMANTIC[form.status])" size="small" variant="flat" data-testid="status-chip">
          {{ ADMIN_PRODUCT_STATUS_LABEL[form.status] }}
        </v-chip>
        <span v-if="form.status === 'STOPPED' && form.saleStopSource" class="text-caption text-medium-emphasis" data-testid="stop-source">
          {{ ADMIN_SALE_STOP_SOURCE_LABEL[form.saleStopSource] }}
        </span>
        <v-menu>
          <template #activator="{ props: activatorProps }">
            <v-btn v-bind="activatorProps" size="small" variant="outlined" :append-icon="mdiDotsVertical" :disabled="statusBusy" data-testid="status-menu">상태 전환</v-btn>
          </template>
          <v-list density="compact" min-width="180">
            <v-list-item
              v-for="target in ADMIN_PRODUCT_STATUS_TARGETS"
              :key="target.value"
              :title="statusTargetTitle(saleState, target.value)"
              :disabled="statusTargetDisabled(target.value)"
              :data-testid="`status-target-${target.value}`"
              @click="requestStatusChange(target.value)"
            />
          </v-list>
        </v-menu>
        <v-divider vertical class="mx-2" />
        <v-switch
          :model-value="form.soldOutManual"
          label="상품 수동 품절"
          color="error"
          hide-details
          inset
          density="compact"
          :disabled="statusBusy"
          data-testid="product-soldout-toggle"
          @update:model-value="(value) => toggleSoldOut(Boolean(value))"
        />
        <span class="text-caption text-medium-emphasis">상태·수동 품절은 즉시 반영됩니다(저장 버튼과 무관).</span>
      </v-card-text>
    </v-card>

    <v-alert v-if="failedStep" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="save-failed-alert">
      <strong>{{ SAVE_STEP_LABEL[failedStep] }}</strong> 단계에서 실패했습니다 — {{ failedMessage }}
      <span v-if="mode === 'edit'"> 내용을 확인한 뒤 다시 저장하세요.</span>
    </v-alert>

    <AdminProductBasicSection v-model="form" :mode="mode" :sellers="sellers" :categories="categories" :seller-name="sellerName" :errors="errors" />

    <v-card class="mb-4" data-testid="section-images">
      <v-card-text class="pa-5">
        <p class="adm-section-title mb-4">이미지</p>
        <AdminProductImageSection
          :images="form.images"
          image-type="GALLERY"
          title="갤러리 이미지"
          description="목록 썸네일·상세 상단 갤러리. 드래그로 순서를 바꾸고 별 아이콘으로 대표를 지정합니다(대표 미지정 시 첫 장)."
          @update:images="(images) => (form.images = images)"
          @update:uploading="(value) => (uploading.GALLERY = value)"
        />
        <v-divider class="my-5" />
        <AdminProductImageSection
          :images="form.images"
          image-type="DETAIL"
          title="상세 이미지"
          description="상품 상세 본문 영역에 순서대로 표시됩니다."
          @update:images="(images) => (form.images = images)"
          @update:uploading="(value) => (uploading.DETAIL = value)"
        />
      </v-card-text>
    </v-card>

    <AdminProductOptionSection v-model="form" :mode="mode" :errors="errors" />

    <!-- 판매가 변경 확인(FE-61): 수정 모드에서 판매가가 바뀐 저장만 한 번 확인한다. -->
    <AdminConfirmDialog
      :open="priceChange !== null"
      test-id="admin-price-change-dialog"
      title="판매가 변경 확인"
      :message="priceChange ? priceChangeMessage(priceChange) : ''"
      :warning-lines="priceChange ? priceChangeWarnings(priceChange) : []"
      warning-emphasis
      confirm-label="저장"
      @confirm="confirmPriceChange"
      @cancel="priceChange = null"
    />

    <div class="d-flex align-center justify-end ga-2 mb-8" data-testid="form-actions">
      <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="form-back">목록으로</v-btn>
      <v-btn color="primary" :prepend-icon="mdiContentSave" :loading="saving" :disabled="!canSave" data-testid="form-save" @click="save">
        {{ anyUploading ? '이미지 업로드 중…' : mode === 'create' ? '등록' : '저장' }}
      </v-btn>
    </div>
    <AdminConfirmDialog
      :open="escalateOpen"
      test-id="admin-escalate-dialog"
      :title="ESCALATE_STOP_TITLE"
      confirm-color="warning"
      :message="escalateConfirmMessage(form.name || form.productPublicId || '')"
      :confirm-label="ESCALATE_STOP_TITLE"
      @confirm="confirmEscalate"
      @cancel="escalateOpen = false"
    />
  </div>
</template>
