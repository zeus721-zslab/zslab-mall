<script setup lang="ts">
import type { SellerProductForm, SellerProductFormErrors, SellerProductFormMode } from '#layers/seller/app/types/seller-product-form'
import type { CategorySummary } from '~/types/category'
import { PRODUCT_NAME_MAX } from '#layers/seller/app/lib/seller-product-form'
import { SELLER_PRODUCT_STATUS_LABEL, SELLER_PRODUCT_STATUS_SEMANTIC } from '#layers/seller/app/lib/constants/seller-product'
import { semanticChipClass } from '#layers/seller/app/lib/constants/semantic'

// 기본정보 섹션(Track 90-C-4·관리자 AdminProductBasicSection 복제·축소). 폼 객체를 v-model로 받아 필드만 바꾼다(변환·검증은 lib).
// 셀러 지정·공급가·판매기간은 없다(BE 계약 6). 수정 모드의 상품 상태는 표시 전용(승인·판매중지는 관리자 소관).
const form = defineModel<SellerProductForm>({ required: true })

const props = defineProps<{
  mode: SellerProductFormMode
  categories: CategorySummary[]
  errors: SellerProductFormErrors
}>()

const categoryItems = computed(() => props.categories.map((category) => ({ value: category.categoryId, title: category.displayName })))

function toNumber(value: string | number | null): number | null {
  if (value === null || value === '') return null
  const parsed = Number(value)
  return Number.isNaN(parsed) ? null : parsed
}
</script>

<template>
  <v-card class="mb-4" data-testid="section-basic">
    <v-card-text class="pa-5">
      <div class="d-flex align-center flex-wrap ga-3 mb-4">
        <p class="slr-section-title mb-0">기본정보</p>
        <template v-if="mode === 'edit' && form.status">
          <v-chip :class="semanticChipClass(SELLER_PRODUCT_STATUS_SEMANTIC[form.status])" size="small" variant="flat" data-testid="status-chip">
            {{ SELLER_PRODUCT_STATUS_LABEL[form.status] }}
          </v-chip>
          <span class="text-caption text-medium-emphasis" data-testid="status-readonly-note">상태(승인·판매중지)는 관리자가 처리하며 여기서 바꿀 수 없습니다.</span>
        </template>
      </div>
      <v-row dense>
        <v-col cols="12" md="6">
          <v-select
            :model-value="form.categoryId"
            :items="categoryItems"
            label="카테고리 *"
            :error-messages="errors.categoryId"
            data-testid="field-category"
            @update:model-value="(value) => (form.categoryId = value ?? null)"
          />
        </v-col>
        <v-col cols="12" md="6">
          <v-text-field
            :model-value="form.basePrice"
            label="기본가(원) *"
            type="number"
            min="0"
            step="1"
            inputmode="numeric"
            hint="옵션 추가금은 조합표에서 따로 입력합니다. 판매중 상품도 즉시 반영됩니다(이미 결제된 주문 금액은 변하지 않음)."
            persistent-hint
            :error-messages="errors.basePrice"
            data-testid="field-base-price"
            @update:model-value="(value) => (form.basePrice = toNumber(value))"
          />
        </v-col>
        <v-col cols="12">
          <v-text-field v-model="form.name" label="상품명 *" :counter="PRODUCT_NAME_MAX" :maxlength="PRODUCT_NAME_MAX" :error-messages="errors.name" data-testid="field-name" />
        </v-col>
        <v-col cols="12">
          <v-textarea v-model="form.description" label="상품 설명" rows="4" auto-grow data-testid="field-description" />
        </v-col>
      </v-row>
    </v-card-text>
  </v-card>
</template>
