<script setup lang="ts">
import { mdiAlertCircleOutline, mdiArrowLeft, mdiContentSave } from '@mdi/js'
import type { SellerProductForm, SellerProductFormErrors, SellerProductFormMode } from '#layers/seller/app/types/seller-product-form'
import type { CategorySummary } from '~/types/category'
import {
  changedSections,
  conflictErrors,
  formSnapshot,
  mapFormFieldErrors,
  sectionSnapshots,
  validateSellerProductForm,
} from '#layers/seller/app/lib/seller-product-form'
import { SELLER_SAVE_STEP_LABEL, SellerSaveStepError, saveSellerProduct, type SellerSaveStep } from '#layers/seller/app/lib/seller-product-save'
import { extractErrorCode, extractErrorStatus, isSellerSuspendedError, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProducts } from '#layers/seller/app/composables/useSellerProducts'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'
import { priceChangeMessage, priceChangeOf, priceChangeWarnings, type PriceChange } from '~/lib/utils/price-change'

/**
 * 셀러 상품 폼 셸(Track 90-C-4·관리자 AdminProductForm 복제·축소·신규/수정 공용). 섹션 3개(기본·이미지·옵션)를 조립하고 검증→저장 오케스트레이션→
 * 토스트→복귀를 담당한다. 관리자의 상태 전환·수동 품절 즉시 반영 카드는 없다(셀러는 상태를 못 바꿈·계약 6). 수정 저장은 바뀐 섹션만 PUT한다.
 * dirty = 저장 시점 스냅샷과 현재 스냅샷 비교. 라우트 이탈(onBeforeRouteLeave)과 새로고침(beforeunload) 모두 경고한다.
 *
 * 에러: 403 SELLER_SUSPENDED → danger 토스트 + 저장 중단(FE-44 §8) · 409 PRODUCT_VARIANT_OPTION_CONFLICT → 해당 variant 행 에러 · 400 fieldErrors → 폼 필드 ·
 * 404 → 부모(notFound) · 다단계 중간 실패 → 어느 단계까지 저장됐는지 alert로 명시(SellerSaveStepError.completedSteps).
 */
const props = defineProps<{
  mode: SellerProductFormMode
  initial: SellerProductForm
  categories: CategorySummary[]
  /** 목록 복귀 경로(?back=·검증된 값). */
  backPath: string
}>()

const emit = defineEmits<{
  /** 등록 성공(모든 단계 완료). 부모가 승인 안내 토스트 + 목록 이동. */
  created: [productPublicId: string]
  /** 등록 후속(이미지) 단계 실패 → 이미 생성된 상품의 수정 화면으로 전환(중복 등록 방지). */
  createdPartially: [productPublicId: string]
  /** 수정 저장 성공. */
  saved: []
  /** 저장 중 404(상품이 사라짐) → 부모가 목록 복귀 + 토스트. */
  notFound: []
}>()

const productsApi = useSellerProducts()
const toast = useSellerToast()

const form = ref<SellerProductForm>(structuredClone(toRaw(props.initial)))
const errors = ref<SellerProductFormErrors>({})
const saving = ref(false)
const uploading = ref({ GALLERY: false, DETAIL: false })
const failedStep = ref<SellerSaveStep | null>(null)
const failedMessage = ref('')
const completedSteps = ref<SellerSaveStep[]>([])
let savedSnapshot = formSnapshot(form.value)
let savedSections = sectionSnapshots(form.value)
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
 * 저장 요청. 검증·변경 판정을 마친 뒤, 수정 모드에서 판매가가 바뀌었으면 확인 다이얼로그를 열고 멈춘다(FE-61).
 * 가격이 그대로거나 신규 등록이면 바로 저장한다.
 */
async function save(): Promise<void> {
  if (!canSave.value) return
  errors.value = validateSellerProductForm(form.value, props.mode)
  if (Object.keys(errors.value).length > 0) {
    toast.warning('입력값을 확인해 주세요.')
    return
  }
  const changed = changedSections(savedSections, form.value)
  if (props.mode === 'edit' && !changed.basic && !changed.images && !changed.variants) {
    toast.info('변경된 내용이 없습니다.')
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
  const changed = changedSections(savedSections, form.value)
  saving.value = true
  failedStep.value = null
  failedMessage.value = ''
  completedSteps.value = []
  try {
    const result = await saveSellerProduct(productsApi, form.value, props.mode, changed)
    savedSnapshot = formSnapshot(form.value)
    savedSections = sectionSnapshots(form.value)
    savedBasePrice = form.value.basePrice
    if (props.mode === 'create') {
      emit('created', result.productPublicId)
    } else {
      toast.success('상품을 저장했습니다.')
      emit('saved')
    }
  } catch (error) {
    handleSaveError(error)
  } finally {
    saving.value = false
  }
}

function handleSaveError(error: unknown): void {
  if (!(error instanceof SellerSaveStepError)) {
    toast.danger(toSellerErrorMessage(error))
    return
  }
  const failure = error.failure
  failedStep.value = error.step
  completedSteps.value = error.completedSteps
  failedMessage.value = toSellerErrorMessage(failure)
  const code = extractErrorCode(failure)

  if (isSellerSuspendedError(failure)) {
    // 정지 셀러: 배너는 useSellerApi가 켜지만 호출부도 danger 토스트로 직접 알리고 저장을 멈춘다(배너에만 의존 금지).
    toast.danger(failedMessage.value)
  } else if (code === 'PRODUCT_VARIANT_OPTION_CONFLICT') {
    const detail = (failure as { data?: { detail?: string } } | null)?.data?.detail
    errors.value = conflictErrors(detail, form.value)
    toast.warning('같은 옵션 조합의 변형이 이미 있습니다(삭제된 조합 포함). 해당 행을 확인하세요.')
  } else if (code === 'VALIDATION_FAILED' || code === 'MALFORMED_REQUEST') {
    const fieldErrors = (failure as { data?: { fieldErrors?: { field: string; message: string }[] } } | null)?.data?.fieldErrors
    const mapped = mapFormFieldErrors(fieldErrors)
    errors.value = Object.keys(mapped).length > 0 ? mapped : { variants: failedMessage.value }
    toast.warning(`${SELLER_SAVE_STEP_LABEL[error.step]} 실패 — 입력값을 확인해 주세요.`)
  } else if (extractErrorStatus(failure) === 404 && props.mode === 'edit' && error.step === 'basic') {
    toast.danger(failedMessage.value)
    emit('notFound')
    return
  } else {
    toast.danger(`${SELLER_SAVE_STEP_LABEL[error.step]} 실패 — ${failedMessage.value}`)
  }

  if (props.mode === 'create' && error.productPublicId) {
    // 상품은 생성됨(PENDING) → 수정 화면으로 전환해 남은 단계(이미지)를 재시도한다. 스냅샷을 갱신해 이탈 경고 없이 넘어간다.
    savedSnapshot = formSnapshot(form.value)
    emit('createdPartially', error.productPublicId)
  }
}

defineExpose({ form, dirty })
</script>

<template>
  <div data-testid="seller-product-form">
    <v-alert v-if="failedStep" type="error" class="mb-4" :icon="mdiAlertCircleOutline" data-testid="save-failed-alert">
      <strong>{{ SELLER_SAVE_STEP_LABEL[failedStep] }}</strong> 단계에서 실패했습니다 — {{ failedMessage }}
      <span v-if="completedSteps.length > 0" data-testid="save-completed-steps">
        (저장됨: {{ completedSteps.map((step) => SELLER_SAVE_STEP_LABEL[step]).join(' · ') }})
      </span>
      <span v-if="mode === 'edit'"> 내용을 확인한 뒤 다시 저장하세요.</span>
    </v-alert>

    <SellerProductBasicSection v-model="form" :mode="mode" :categories="categories" :errors="errors" />

    <v-card class="mb-4" data-testid="section-images">
      <v-card-text class="pa-5">
        <p class="slr-section-title mb-4">이미지</p>
        <SellerProductImageSection
          :images="form.images"
          image-type="GALLERY"
          title="갤러리 이미지"
          description="목록 썸네일·상세 상단 갤러리. 드래그로 순서를 바꾸고 별 아이콘으로 대표를 지정합니다(대표 미지정 시 첫 장)."
          @update:images="(images) => (form.images = images)"
          @update:uploading="(value) => (uploading.GALLERY = value)"
        />
        <v-divider class="my-5" />
        <SellerProductImageSection
          :images="form.images"
          image-type="DETAIL"
          title="상세 이미지"
          description="상품 상세 본문 영역에 순서대로 표시됩니다."
          @update:images="(images) => (form.images = images)"
          @update:uploading="(value) => (uploading.DETAIL = value)"
        />
      </v-card-text>
    </v-card>

    <SellerProductOptionSection v-model="form" :mode="mode" :errors="errors" />

    <!-- 판매가 변경 확인(FE-61): 수정 모드에서 판매가가 바뀐 저장만 한 번 확인한다. 셀러 레이어에는 공용 확인 다이얼로그가 없어 여기서 조립한다. -->
    <v-dialog :model-value="priceChange !== null" max-width="440" @update:model-value="(value) => !value && (priceChange = null)">
      <v-card data-testid="seller-price-change-dialog">
        <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">판매가 변경 확인</v-card-title>
        <v-card-text class="px-5 text-body-2" data-testid="price-change-message">{{ priceChange ? priceChangeMessage(priceChange) : '' }}</v-card-text>
        <v-card-text v-if="priceChange && priceChangeWarnings(priceChange).length > 0" class="px-5 pt-0">
          <v-alert type="warning" variant="tonal" density="compact" data-testid="price-change-warning">
            <p v-for="line in priceChangeWarnings(priceChange)" :key="line" class="text-body-2 font-weight-bold mb-0">{{ line }}</p>
          </v-alert>
        </v-card-text>
        <v-card-actions class="px-5 pb-4">
          <v-spacer />
          <v-btn variant="text" data-testid="price-change-cancel" @click="priceChange = null">취소</v-btn>
          <v-btn color="primary" variant="flat" data-testid="price-change-ok" @click="confirmPriceChange">저장</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <div class="d-flex align-center justify-end ga-2 mb-8" data-testid="form-actions">
      <v-btn variant="text" :prepend-icon="mdiArrowLeft" :to="backPath" data-testid="form-back">목록으로</v-btn>
      <v-btn color="primary" :prepend-icon="mdiContentSave" :loading="saving" :disabled="!canSave" data-testid="form-save" @click="save">
        {{ anyUploading ? '이미지 업로드 중…' : mode === 'create' ? '등록' : '저장' }}
      </v-btn>
    </div>
  </div>
</template>
