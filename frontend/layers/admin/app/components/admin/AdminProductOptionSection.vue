<script setup lang="ts">
import { mdiPlus, mdiTrashCanOutline } from '@mdi/js'
import type { ProductForm, ProductFormErrors, ProductFormOptionGroup } from '#layers/admin/app/types/admin-product-form'
import {
  MAX_OPTION_GROUPS,
  includedVariants,
  nextLocalId,
  regenerateVariants,
  singleVariant,
  suggestVariantCode,
  variantLabel,
} from '#layers/admin/app/lib/admin-product-form'

// 옵션 섹션(FE-26). 단일/옵션 전환·그룹(최대 3)·값 칩·조합표. 그룹/값이 바뀔 때마다 regenerateVariants로 조합표를 재계산한다(기존 행 입력 유지·
// 사라진 기존 variant는 removed 표시 = 저장 시 soft-delete·D9 α). 수정 모드에서는 그룹 구조(이름·개수) 변경 불가(BE 계약)·값 추가만 가능.
const form = defineModel<ProductForm>({ required: true })
const props = defineProps<{ mode: 'create' | 'edit'; errors: ProductFormErrors }>()

const switchConfirm = ref<null | boolean>(null) // 전환 목표(true=옵션 상품)
const bulkAdditional = ref<number | null>(null)
const bulkStock = ref<number | null>(null)

const groupsLocked = computed(() => props.mode === 'edit' && form.value.optionGroups.some((group) => group.optionGroupId !== null))
const activeVariants = computed(() => form.value.variants.filter((variant) => !variant.removed))
const removedVariants = computed(() => form.value.variants.filter((variant) => variant.removed))

function requestSwitch(hasOptions: boolean): void {
  if (hasOptions === form.value.hasOptions) return
  const hasInput = form.value.hasOptions
    ? form.value.optionGroups.length > 0
    : form.value.variants.some((variant) => variant.stock !== 0 || variant.additionalPrice !== 0)
  if (hasInput) {
    switchConfirm.value = hasOptions
    return
  }
  applySwitch(hasOptions)
}

function applySwitch(hasOptions: boolean): void {
  switchConfirm.value = null
  form.value.hasOptions = hasOptions
  if (hasOptions) {
    form.value.optionGroups = [newGroup()]
    form.value.variants = []
  } else {
    form.value.optionGroups = []
    form.value.variants = [singleVariant()]
  }
}

function newGroup(): ProductFormOptionGroup {
  return { localId: nextLocalId('g'), optionGroupId: null, name: '', values: [] }
}

function addGroup(): void {
  if (form.value.optionGroups.length >= MAX_OPTION_GROUPS) return
  form.value.optionGroups = [...form.value.optionGroups, newGroup()]
  recompute()
}

function removeGroup(localId: string): void {
  form.value.optionGroups = form.value.optionGroups.filter((group) => group.localId !== localId)
  recompute()
}

/** v-combobox chips → 값 목록 동기화(기존 값의 localId·optionValueId 보존·새 문자열은 신규 값). */
function setValues(group: ProductFormOptionGroup, texts: string[]): void {
  const existingByText = new Map(group.values.map((value) => [value.value, value]))
  group.values = texts
    .map((text) => text.trim())
    .filter((text, index, all) => text !== '' && all.indexOf(text) === index)
    .map((text) => existingByText.get(text) ?? { localId: nextLocalId('o'), optionValueId: null, value: text })
  recompute()
}

function recompute(): void {
  form.value.variants = regenerateVariants(form.value.optionGroups, form.value.variants)
  // 새 조합의 코드가 비어 있으면 옵션값 기반으로 제안한다(운영자가 바꿀 수 있음).
  form.value.variants = form.value.variants.map((variant) =>
    variant.variantCode === '' ? { ...variant, variantCode: suggestVariantCode(variant, form.value.optionGroups) } : variant)
}

function applyBulk(): void {
  form.value.variants = form.value.variants.map((variant) => variant.removed
    ? variant
    : {
      ...variant,
      additionalPrice: bulkAdditional.value ?? variant.additionalPrice,
      stock: bulkStock.value ?? variant.stock,
    })
}

function toNumber(value: string | number | null, fallback: number): number {
  const parsed = Number(value)
  return value === null || value === '' || Number.isNaN(parsed) ? fallback : parsed
}

/** 검증 에러 키(variants.N.*)는 저장 대상(제외 행 뺀) 순서 기준이다. */
function activeIndex(localId: string): number {
  return includedVariants(form.value).findIndex((variant) => variant.localId === localId)
}

const includedCount = computed(() => includedVariants(form.value).length)
</script>

<template>
  <v-card class="mb-4" data-testid="section-options">
    <v-card-text class="pa-5">
      <div class="d-flex align-center justify-space-between flex-wrap ga-3 mb-4">
        <p class="adm-section-title mb-0">옵션·재고</p>
        <v-btn-toggle :model-value="form.hasOptions" mandatory density="comfortable" color="primary" variant="outlined" divided :disabled="groupsLocked">
          <v-btn :value="false" data-testid="option-mode-single" @click="requestSwitch(false)">단일 상품</v-btn>
          <v-btn :value="true" data-testid="option-mode-options" @click="requestSwitch(true)">옵션 상품</v-btn>
        </v-btn-toggle>
      </div>
      <p v-if="groupsLocked" class="text-caption text-medium-emphasis mb-3">수정 시 옵션 그룹(이름·개수)은 바꿀 수 없습니다. 옵션값 추가·삭제와 조합별 값만 변경됩니다.</p>

      <template v-if="form.hasOptions">
        <p v-if="errors.optionGroups" class="text-caption text-error mb-2">{{ errors.optionGroups }}</p>
        <div v-for="(group, groupIndex) in form.optionGroups" :key="group.localId" class="d-flex align-start ga-2 mb-2" data-testid="option-group">
          <v-text-field
            v-model="group.name"
            :label="`옵션 그룹 ${groupIndex + 1}`"
            placeholder="예: 색상"
            maxlength="50"
            :readonly="groupsLocked"
            :error-messages="errors[`optionGroups.${groupIndex}.name`]"
            style="max-width: 200px"
            hide-details="auto"
            data-testid="option-group-name"
            @update:model-value="recompute"
          />
          <v-combobox
            :model-value="group.values.map((value) => value.value)"
            label="옵션값 (입력 후 Enter)"
            placeholder="예: 블랙"
            multiple
            chips
            closable-chips
            hide-details="auto"
            :error-messages="errors[`optionGroups.${groupIndex}.values`]"
            class="flex-grow-1"
            data-testid="option-group-values"
            @update:model-value="(texts) => setValues(group, texts as string[])"
          />
          <v-btn v-if="!groupsLocked" :icon="mdiTrashCanOutline" variant="text" aria-label="그룹 삭제" data-testid="option-group-remove" @click="removeGroup(group.localId)" />
        </div>
        <v-btn v-if="!groupsLocked && form.optionGroups.length < MAX_OPTION_GROUPS" variant="text" :prepend-icon="mdiPlus" size="small" data-testid="option-group-add" @click="addGroup">
          옵션 그룹 추가 ({{ form.optionGroups.length }}/{{ MAX_OPTION_GROUPS }})
        </v-btn>
      </template>

      <div class="d-flex align-center justify-space-between flex-wrap ga-2 mt-5 mb-2">
        <p class="text-subtitle-2 font-weight-bold mb-0">
          조합 <span class="text-medium-emphasis font-weight-regular" data-testid="variant-count">{{ activeVariants.length }}개</span>
          <span v-if="includedCount !== activeVariants.length" class="text-caption text-medium-emphasis">(저장 {{ includedCount }}개·제외 {{ activeVariants.length - includedCount }}개)</span>
        </p>
        <!-- 전체 적용 입력은 표 헤더 줄 인라인이라 compact(의도된 예외) -->
        <div v-if="form.hasOptions && activeVariants.length > 1" class="d-flex align-center ga-2">
          <v-text-field v-model.number="bulkAdditional" label="추가금 전체" type="number" density="compact" hide-details style="width: 130px" data-testid="bulk-additional" />
          <v-text-field v-model.number="bulkStock" label="재고 전체" type="number" density="compact" hide-details style="width: 110px" data-testid="bulk-stock" />
          <v-btn size="small" variant="outlined" data-testid="bulk-apply" @click="applyBulk">전체 적용</v-btn>
        </div>
      </div>
      <p v-if="errors.variants" class="text-caption text-error mb-2">{{ errors.variants }}</p>
      <p class="text-caption text-medium-emphasis mb-2">한 번 생성된 조합은 삭제 후 다시 만들 수 없습니다 — 만들지 않을 신규 조합은 "제외"에 체크하세요.</p>

      <!-- 조합표 안 입력은 행 높이를 낮추기 위해 compact + hide-details="auto"(전역 comfortable의 의도된 예외) -->
      <v-table density="compact" class="adm-variant-table" data-testid="variant-table">
        <thead>
          <tr>
            <th>조합</th>
            <th style="min-width: 160px">코드</th>
            <th style="min-width: 120px">추가금(원)</th>
            <th style="min-width: 110px">{{ mode === 'edit' ? '가용 재고' : '초기 재고' }}</th>
            <th>수동 품절</th>
            <th>사용</th>
            <th>제외</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="variant in activeVariants" :key="variant.localId" :class="{ 'adm-variant-row--excluded': variant.excluded }" data-testid="variant-row">
            <td class="text-body-2">
              {{ variantLabel(variant, form.optionGroups) }}
              <v-chip v-if="variant.variantPublicId === null && mode === 'edit'" size="x-small" class="ml-1 adm-chip adm-chip--info">신규</v-chip>
            </td>
            <td>
              <v-text-field v-model="variant.variantCode" density="compact" hide-details="auto" maxlength="50" :disabled="variant.excluded" :error-messages="errors[`variants.${activeIndex(variant.localId)}.variantCode`]" data-testid="variant-code" />
            </td>
            <td>
              <v-text-field :model-value="variant.additionalPrice" type="number" min="0" density="compact" hide-details="auto" :disabled="variant.excluded" :error-messages="errors[`variants.${activeIndex(variant.localId)}.additionalPrice`]" data-testid="variant-additional" @update:model-value="(value) => (variant.additionalPrice = toNumber(value, 0))" />
            </td>
            <td>
              <v-text-field :model-value="variant.stock" type="number" min="0" density="compact" hide-details="auto" :disabled="variant.excluded" :error-messages="errors[`variants.${activeIndex(variant.localId)}.stock`]" :hint="variant.stockOnServer !== null && variant.stock !== variant.stockOnServer ? `서버 ${variant.stockOnServer} → 조정 ${variant.stock - variant.stockOnServer > 0 ? '+' : ''}${variant.stock - variant.stockOnServer}` : undefined" persistent-hint data-testid="variant-stock" @update:model-value="(value) => (variant.stock = toNumber(value, 0))" />
            </td>
            <td><v-switch v-model="variant.soldOutManual" color="error" density="compact" hide-details inset :disabled="variant.excluded" data-testid="variant-soldout" /></td>
            <td><v-switch v-model="variant.enabled" color="primary" density="compact" hide-details inset :disabled="variant.excluded" data-testid="variant-enabled" /></td>
            <td>
              <!-- 제외는 신규 행(서버 미생성)에만 — 기존 행은 옵션값 삭제로만 soft-delete된다 -->
              <v-checkbox v-if="variant.variantPublicId === null" v-model="variant.excluded" density="compact" hide-details :aria-label="`${variantLabel(variant, form.optionGroups)} 제외`" data-testid="variant-exclude" />
            </td>
          </tr>
          <tr v-for="variant in removedVariants" :key="variant.localId" class="adm-variant-row--removed" data-testid="variant-row-removed">
            <td class="text-body-2" colspan="7">{{ variantLabel(variant, form.optionGroups) }} · {{ variant.variantCode }} — 옵션값 삭제로 저장 시 제거됩니다(soft-delete)</td>
          </tr>
        </tbody>
      </v-table>
    </v-card-text>

    <AdminConfirmDialog
      :open="switchConfirm !== null"
      test-id="option-switch-dialog"
      title="상품 유형 전환"
      :message="switchConfirm ? '단일 상품 입력(재고·추가금)이 초기화되고 옵션 그룹 입력으로 바뀝니다.' : '입력한 옵션 그룹·값·조합이 모두 삭제되고 단일 상품으로 바뀝니다.'"
      confirm-label="전환"
      confirm-color="warning"
      @confirm="applySwitch(switchConfirm as boolean)"
      @cancel="switchConfirm = null"
    />
  </v-card>
</template>
