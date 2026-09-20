<script setup lang="ts">
import { mdiOpenInNew, mdiPlus, mdiTrashCanOutline } from '@mdi/js'
import type { SellerFormOptionGroup, SellerFormVariant, SellerProductForm, SellerProductFormErrors, SellerProductFormMode } from '#layers/seller/app/types/seller-product-form'
import {
  MAX_OPTION_GROUPS,
  SELLER_SKU_MAX,
  VARIANT_CODE_MAX,
  includedVariants,
  nextLocalId,
  regenerateVariants,
  singleVariant,
  suggestVariantCode,
  variantLabel,
} from '#layers/seller/app/lib/seller-product-form'
import { SELLER_INVENTORY_PATH } from '#layers/seller/app/lib/seller-back-path'

/**
 * 옵션 섹션(Track 90-C-4·관리자 AdminProductOptionSection 복제·BE 계약 반영).
 * - 등록 모드: 단일/옵션 전환·그룹(최대 3)·값 칩·조합표(코드·SKU·추가금·초기 재고·제외). 그룹/값이 바뀔 때마다 regenerateVariants로 재계산.
 * - 수정 모드: 그룹·값 구조 **잠금**(계약 2·표시만) · 기존 variant는 메타(코드·SKU·추가금·수동품절·사용=HIDDEN 토글)만 편집하고 재고는 **읽기 전용**
 *   (계약 1·재고 화면 링크) · 아직 없는 조합은 "추가" 체크로만 신규 생성(초기 재고 입력) · 삭제 없음(계약 3).
 */
const form = defineModel<SellerProductForm>({ required: true })
const props = defineProps<{ mode: SellerProductFormMode; errors: SellerProductFormErrors }>()

const switchConfirm = ref<null | boolean>(null) // 전환 목표(true=옵션 상품)

const locked = computed(() => props.mode === 'edit')
const existingVariants = computed(() => form.value.variants.filter((variant) => variant.variantPublicId !== null))
const newVariants = computed(() => form.value.variants.filter((variant) => variant.variantPublicId === null))
const includedCount = computed(() => includedVariants(form.value).length)
const inventoryLink = computed(() => (form.value.productPublicId ? `${SELLER_INVENTORY_PATH}?productPublicId=${form.value.productPublicId}` : SELLER_INVENTORY_PATH))

function requestSwitch(hasOptions: boolean): void {
  if (locked.value || hasOptions === form.value.hasOptions) return
  const hasInput = form.value.hasOptions
    ? form.value.optionGroups.length > 0
    : form.value.variants.some((variant) => variant.initialStock !== 0 || variant.additionalPrice !== 0)
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

function newGroup(): SellerFormOptionGroup {
  return { localId: nextLocalId('g'), optionGroupId: null, name: '', values: [] }
}

function addGroup(): void {
  if (locked.value || form.value.optionGroups.length >= MAX_OPTION_GROUPS) return
  form.value.optionGroups = [...form.value.optionGroups, newGroup()]
  recompute()
}

function removeGroup(localId: string): void {
  if (locked.value) return
  form.value.optionGroups = form.value.optionGroups.filter((group) => group.localId !== localId)
  recompute()
}

/** v-combobox chips → 값 목록 동기화(기존 값의 localId 보존·새 문자열은 신규 값). 등록 모드 전용. */
function setValues(group: SellerFormOptionGroup, texts: string[]): void {
  if (locked.value) return
  const existingByText = new Map(group.values.map((value) => [value.value, value]))
  group.values = texts
    .map((text) => text.trim())
    .filter((text, index, all) => text !== '' && all.indexOf(text) === index)
    .map((text) => existingByText.get(text) ?? { localId: nextLocalId('o'), optionValueId: null, value: text })
  recompute()
}

function recompute(): void {
  form.value.variants = regenerateVariants(form.value.optionGroups, form.value.variants)
  // 새 조합의 코드가 비어 있으면 옵션값 기반으로 제안한다(셀러가 바꿀 수 있음).
  form.value.variants = form.value.variants.map((variant) =>
    variant.variantCode === '' ? { ...variant, variantCode: suggestVariantCode(variant, form.value.optionGroups) } : variant)
}

/** 수정 모드 "추가" 체크: excluded 반전 + 코드 제안. */
function setAdded(variant: SellerFormVariant, added: boolean): void {
  variant.excluded = !added
  if (added && variant.variantCode === '') variant.variantCode = suggestVariantCode(variant, form.value.optionGroups)
}

function toNumber(value: string | number | null, fallback: number): number {
  const parsed = Number(value)
  return value === null || value === '' || Number.isNaN(parsed) ? fallback : parsed
}

/** 검증 에러 키(variants.N.*)는 저장 대상(제외 행 뺀) 순서 기준이다. */
function includedIndex(localId: string): number {
  return includedVariants(form.value).findIndex((variant) => variant.localId === localId)
}

function rowError(variant: SellerFormVariant, field: string): string | undefined {
  return props.errors[`variants.${includedIndex(variant.localId)}.${field}`]
}
</script>

<template>
  <v-card class="mb-4" data-testid="section-options">
    <v-card-text class="pa-5">
      <div class="d-flex align-center justify-space-between flex-wrap ga-3 mb-4">
        <p class="slr-section-title mb-0">옵션·재고</p>
        <v-btn-toggle v-if="!locked" :model-value="form.hasOptions" mandatory density="comfortable" color="primary" variant="outlined" divided>
          <v-btn :value="false" data-testid="option-mode-single" @click="requestSwitch(false)">단일 상품</v-btn>
          <v-btn :value="true" data-testid="option-mode-options" @click="requestSwitch(true)">옵션 상품</v-btn>
        </v-btn-toggle>
      </div>

      <!-- 수정 모드: 옵션 구조 잠금 안내(그룹·값은 표시만) -->
      <v-alert v-if="locked" type="info" variant="tonal" density="compact" class="mb-4" data-testid="option-locked-notice">
        옵션 그룹·값 구조는 등록 후 바꿀 수 없습니다. 기존 옵션값 조합으로만 새 변형을 추가할 수 있고, 변형은 삭제 대신 "사용"을 꺼서 비활성화합니다.
        기존 변형의 재고는 <NuxtLink :to="inventoryLink" data-testid="option-inventory-link">재고 화면</NuxtLink>에서 입고·출고로 조정합니다.
      </v-alert>

      <template v-if="form.hasOptions">
        <p v-if="errors.optionGroups" class="text-caption text-error mb-2">{{ errors.optionGroups }}</p>
        <div v-for="(group, groupIndex) in form.optionGroups" :key="group.localId" class="d-flex align-start ga-2 mb-2" data-testid="option-group">
          <template v-if="locked">
            <v-text-field :model-value="group.name" :label="`옵션 그룹 ${groupIndex + 1}`" readonly hide-details style="max-width: 200px" data-testid="option-group-name-locked" />
            <div class="d-flex align-center flex-wrap ga-1 pt-3 flex-grow-1" data-testid="option-group-values-locked">
              <v-chip v-for="value in group.values" :key="value.localId" size="small" variant="tonal">{{ value.value }}</v-chip>
            </div>
          </template>
          <template v-else>
            <v-text-field
              v-model="group.name"
              :label="`옵션 그룹 ${groupIndex + 1}`"
              placeholder="예: 색상"
              maxlength="50"
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
            <v-btn :icon="mdiTrashCanOutline" variant="text" aria-label="그룹 삭제" data-testid="option-group-remove" @click="removeGroup(group.localId)" />
          </template>
        </div>
        <v-btn v-if="!locked && form.optionGroups.length < MAX_OPTION_GROUPS" variant="text" :prepend-icon="mdiPlus" size="small" data-testid="option-group-add" @click="addGroup">
          옵션 그룹 추가 ({{ form.optionGroups.length }}/{{ MAX_OPTION_GROUPS }})
        </v-btn>
      </template>

      <div class="d-flex align-center justify-space-between flex-wrap ga-2 mt-5 mb-2">
        <p class="text-subtitle-2 font-weight-bold mb-0">
          조합 <span class="text-medium-emphasis font-weight-regular" data-testid="variant-count">{{ form.variants.length }}개</span>
          <span v-if="includedCount !== form.variants.length" class="text-caption text-medium-emphasis">(저장 {{ includedCount }}개)</span>
        </p>
      </div>
      <p v-if="errors.variants" class="text-caption text-error mb-2">{{ errors.variants }}</p>
      <p v-if="!locked" class="text-caption text-medium-emphasis mb-2">등록 후에는 옵션 구조를 바꿀 수 없고 조합도 삭제할 수 없습니다 — 만들지 않을 조합은 "제외"에 체크하세요.</p>

      <!-- 조합표 안 입력은 행 높이를 낮추기 위해 compact + hide-details="auto"(전역 comfortable의 의도된 예외) -->
      <v-table density="compact" class="slr-variant-table" data-testid="variant-table">
        <thead>
          <tr>
            <th>조합</th>
            <th style="min-width: 150px">코드</th>
            <th style="min-width: 130px">SKU</th>
            <th style="min-width: 120px">추가금(원)</th>
            <th style="min-width: 150px">{{ locked ? '재고(읽기 전용)' : '초기 재고' }}</th>
            <template v-if="locked">
              <th>수동 품절</th>
              <th>사용</th>
              <th>추가</th>
            </template>
            <th v-else>제외</th>
          </tr>
        </thead>
        <tbody>
          <!-- 기존 variant(수정 모드): 메타만 편집·재고 읽기 전용 -->
          <tr
            v-for="variant in existingVariants"
            :key="variant.localId"
            :class="{ 'slr-variant-row--hidden': !variant.enabled }"
            data-testid="variant-row"
            data-kind="existing"
          >
            <td class="text-body-2">
              {{ variantLabel(variant, form.optionGroups) }}
              <span v-if="rowError(variant, 'combo')" class="text-caption text-error d-block">{{ rowError(variant, 'combo') }}</span>
            </td>
            <td><v-text-field v-model="variant.variantCode" density="compact" hide-details="auto" :maxlength="VARIANT_CODE_MAX" :error-messages="rowError(variant, 'variantCode')" data-testid="variant-code" /></td>
            <td><v-text-field v-model="variant.sellerSku" density="compact" hide-details="auto" :maxlength="SELLER_SKU_MAX" :error-messages="rowError(variant, 'sellerSku')" data-testid="variant-sku" /></td>
            <td><v-text-field :model-value="variant.additionalPrice" type="number" min="0" density="compact" hide-details="auto" :error-messages="rowError(variant, 'additionalPrice')" data-testid="variant-additional" @update:model-value="(value) => (variant.additionalPrice = toNumber(value, 0))" /></td>
            <td class="text-body-2" data-testid="variant-stock-readonly">
              <template v-if="variant.stockOnServer">
                보유 {{ variant.stockOnServer.onHand }} · 예약 {{ variant.stockOnServer.reserved }} · <strong>가용 {{ variant.stockOnServer.available }}</strong>
                <NuxtLink :to="inventoryLink" class="text-caption d-inline-flex align-center ga-1 ml-1" data-testid="variant-stock-link">
                  조정 <v-icon :icon="mdiOpenInNew" size="12" />
                </NuxtLink>
              </template>
            </td>
            <td><v-switch v-model="variant.soldoutManual" color="error" density="compact" hide-details inset data-testid="variant-soldout" /></td>
            <td><v-switch v-model="variant.enabled" color="primary" density="compact" hide-details inset :aria-label="`${variantLabel(variant, form.optionGroups)} 사용(끄면 비활성)`" data-testid="variant-enabled" /></td>
            <td />
          </tr>
          <!-- 신규 variant: 등록 모드=조합표 전체(제외 체크) / 수정 모드=아직 없는 조합(추가 체크) -->
          <tr
            v-for="variant in newVariants"
            :key="variant.localId"
            :class="{ 'slr-variant-row--excluded': variant.excluded }"
            data-testid="variant-row"
            data-kind="new"
          >
            <td class="text-body-2">
              {{ variantLabel(variant, form.optionGroups) }}
              <v-chip v-if="locked" size="x-small" class="ml-1 slr-chip slr-chip--info">신규</v-chip>
              <span v-if="rowError(variant, 'combo')" class="text-caption text-error d-block">{{ rowError(variant, 'combo') }}</span>
            </td>
            <td><v-text-field v-model="variant.variantCode" density="compact" hide-details="auto" :maxlength="VARIANT_CODE_MAX" :disabled="variant.excluded" :error-messages="rowError(variant, 'variantCode')" data-testid="variant-code" /></td>
            <td><v-text-field v-model="variant.sellerSku" density="compact" hide-details="auto" :maxlength="SELLER_SKU_MAX" :disabled="variant.excluded" :error-messages="rowError(variant, 'sellerSku')" data-testid="variant-sku" /></td>
            <td><v-text-field :model-value="variant.additionalPrice" type="number" min="0" density="compact" hide-details="auto" :disabled="variant.excluded" :error-messages="rowError(variant, 'additionalPrice')" data-testid="variant-additional" @update:model-value="(value) => (variant.additionalPrice = toNumber(value, 0))" /></td>
            <td><v-text-field :model-value="variant.initialStock" type="number" min="0" density="compact" hide-details="auto" :disabled="variant.excluded" :error-messages="rowError(variant, 'initialStock')" data-testid="variant-initial-stock" @update:model-value="(value) => (variant.initialStock = toNumber(value, 0))" /></td>
            <template v-if="locked">
              <td /><td />
              <td><v-checkbox :model-value="!variant.excluded" density="compact" hide-details :aria-label="`${variantLabel(variant, form.optionGroups)} 추가`" data-testid="variant-add" @update:model-value="(value) => setAdded(variant, Boolean(value))" /></td>
            </template>
            <td v-else>
              <v-checkbox v-model="variant.excluded" density="compact" hide-details :aria-label="`${variantLabel(variant, form.optionGroups)} 제외`" data-testid="variant-exclude" />
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card-text>

    <v-dialog :model-value="switchConfirm !== null" max-width="440" @update:model-value="(value: boolean) => !value && (switchConfirm = null)">
      <v-card data-testid="option-switch-dialog">
        <v-card-title class="text-subtitle-1 font-weight-bold pt-5 px-5">상품 유형 전환</v-card-title>
        <v-card-text class="px-5 text-body-2">
          {{ switchConfirm ? '단일 상품 입력(재고·추가금)이 초기화되고 옵션 그룹 입력으로 바뀝니다.' : '입력한 옵션 그룹·값·조합이 모두 삭제되고 단일 상품으로 바뀝니다.' }}
        </v-card-text>
        <v-card-actions class="px-5 pb-4">
          <v-spacer />
          <v-btn variant="text" data-testid="option-switch-cancel" @click="switchConfirm = null">취소</v-btn>
          <v-btn color="warning" variant="flat" data-testid="option-switch-confirm" @click="applySwitch(switchConfirm as boolean)">전환</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-card>
</template>
