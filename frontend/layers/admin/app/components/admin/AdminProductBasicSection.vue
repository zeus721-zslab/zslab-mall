<script setup lang="ts">
import type { ProductForm, ProductFormErrors } from '#layers/admin/app/types/admin-product-form'
import type { AdminSellerSummary } from '#layers/admin/app/types/admin-product'
import type { CategorySummary } from '~/types/category'

// 기본정보·판매설정 섹션(FE-26). 폼 객체를 v-model로 받아 필드만 바꾼다(변환·검증은 lib). 셀러는 수정 시 불변(BE PUT에 없음)이라 읽기 전용.
const form = defineModel<ProductForm>({ required: true })

const props = defineProps<{
  mode: 'create' | 'edit'
  sellers: AdminSellerSummary[]
  categories: CategorySummary[]
  errors: ProductFormErrors
  sellerName?: string
}>()

const sellerItems = computed(() => props.sellers.map((seller) => ({ value: seller.sellerPublicId, title: seller.companyName })))
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
      <p class="adm-section-title mb-4">기본정보</p>
      <v-row dense>
        <v-col cols="12" md="6">
          <v-autocomplete
            v-if="mode === 'create'"
            :model-value="form.sellerPublicId"
            :items="sellerItems"
            label="셀러 *"
            placeholder="회사명으로 검색"
            :error-messages="errors.sellerPublicId"
            clearable
            data-testid="field-seller"
            @update:model-value="(value) => (form.sellerPublicId = value ?? null)"
          />
          <v-text-field v-else :model-value="sellerName ?? form.sellerPublicId ?? ''" label="셀러 (변경 불가)" readonly data-testid="field-seller-readonly" />
        </v-col>
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
        <v-col cols="12">
          <v-text-field v-model="form.name" label="상품명 *" counter="200" maxlength="200" :error-messages="errors.name" data-testid="field-name" />
        </v-col>
        <v-col cols="12" md="6">
          <v-text-field
            :model-value="form.basePrice"
            label="판매가(원) *"
            type="number"
            min="0"
            inputmode="numeric"
            :error-messages="errors.basePrice"
            data-testid="field-base-price"
            @update:model-value="(value) => (form.basePrice = toNumber(value))"
          />
        </v-col>
        <v-col cols="12" md="6">
          <v-text-field
            :model-value="form.supplyPrice"
            label="공급가(원) — 참고용"
            type="number"
            min="0"
            inputmode="numeric"
            hint="표시·참고용 값입니다. 정산은 셀러 수수료율 기준으로 계산되며 이 값을 쓰지 않습니다."
            persistent-hint
            :error-messages="errors.supplyPrice"
            data-testid="field-supply-price"
            @update:model-value="(value) => (form.supplyPrice = toNumber(value))"
          />
        </v-col>
        <v-col cols="12">
          <v-textarea v-model="form.description" label="상품 설명" rows="4" auto-grow data-testid="field-description" />
        </v-col>
      </v-row>

      <p class="adm-section-title mt-6 mb-4">판매 설정</p>
      <v-row dense align="center">
        <v-col cols="12" md="4">
          <v-text-field
            v-model="form.saleStartAt"
            label="판매 시작"
            type="datetime-local"
            hint="비우면 즉시 판매"
            persistent-hint
            :error-messages="errors.saleStartAt"
            data-testid="field-sale-start"
          />
        </v-col>
        <v-col cols="12" md="4">
          <v-text-field
            v-model="form.saleEndAt"
            label="판매 종료"
            type="datetime-local"
            :disabled="form.noEndDate"
            :error-messages="errors.saleEndAt"
            data-testid="field-sale-end"
          />
        </v-col>
        <v-col cols="12" md="4">
          <v-switch v-model="form.noEndDate" label="종료일 없음(무기한)" color="primary" hide-details inset data-testid="field-no-end" />
        </v-col>
      </v-row>
    </v-card-text>
  </v-card>
</template>
