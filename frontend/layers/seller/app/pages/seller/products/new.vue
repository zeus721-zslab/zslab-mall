<script setup lang="ts">
import type { CategorySummary } from '~/types/category'
import { createEmptySellerProductForm } from '#layers/seller/app/lib/seller-product-form'
import { SELLER_PRODUCTS_PATH, resolveBackPath } from '#layers/seller/app/lib/seller-back-path'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '상품 등록 · zslab-mall 셀러' })

// 상품 등록(Track 90-C-4·관리자 new.vue 복제). 카테고리 옵션(공개 API)을 먼저 받아 폼을 띄운다. 등록 결과는 PENDING(관리자 승인 전까지 미노출·계약 5)이라
// 성공 안내에 명시하고 목록으로 이동한다. 등록 후속(이미지) 단계 실패 시 생성된 상품의 수정 화면으로 전환한다(중복 등록 방지).
const route = useRoute()
const toast = useSellerToast()
const backPath = computed(() => resolveBackPath(route.query.back, SELLER_PRODUCTS_PATH))

const { data: categoryData, error: categoryError, refresh: reloadCategories, pending: categoriesLoading } = useCategories()
const categories = computed<CategorySummary[]>(() => categoryData.value ?? [])

function onCreated(): void {
  toast.success('상품을 등록했습니다. 관리자 승인 후 판매 화면에 노출됩니다(승인대기).')
  void navigateTo(backPath.value)
}

function onCreatedPartially(productPublicId: string): void {
  void navigateTo({ path: `${SELLER_PRODUCTS_PATH}/${productPublicId}`, query: { back: backPath.value, partial: '1' } }, { replace: true })
}
</script>

<template>
  <div data-testid="seller-product-new">
    <SellerPageHeader title="상품 등록" description="기본정보·이미지·옵션을 입력하고 등록하면 승인대기 상태로 생성됩니다. 관리자 승인 후 판매 화면에 노출됩니다." />
    <v-card v-if="categoriesLoading" class="mb-4"><v-card-text><v-skeleton-loader type="article" /></v-card-text></v-card>
    <v-alert v-else-if="categoryError" type="error" class="mb-4" data-testid="options-error">
      카테고리 목록을 불러오지 못했습니다. <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="reloadCategories()">다시 시도</v-btn>
    </v-alert>
    <SellerProductForm
      v-else
      mode="create"
      :initial="createEmptySellerProductForm()"
      :categories="categories"
      :back-path="backPath"
      @created="onCreated"
      @created-partially="onCreatedPartially"
    />
  </div>
</template>
