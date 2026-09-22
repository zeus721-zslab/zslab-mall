<script setup lang="ts">
import type { SellerProductDetail } from '#layers/seller/app/types/seller-product'
import type { SellerProductForm } from '#layers/seller/app/types/seller-product-form'
import type { SellerSaleAction } from '#layers/seller/app/lib/constants/seller-product'
import type { CategorySummary } from '~/types/category'
import { toSellerProductForm } from '#layers/seller/app/lib/seller-product-form'
import { SELLER_PRODUCTS_PATH, resolveBackPath } from '#layers/seller/app/lib/seller-back-path'
import { extractErrorCode, toSellerErrorMessage } from '#layers/seller/app/lib/seller-error-message'
import { useSellerProducts } from '#layers/seller/app/composables/useSellerProducts'
import { useSellerToast } from '#layers/seller/app/composables/useSellerToast'

definePageMeta({ layout: 'seller', middleware: ['seller', 'seller-vuetify'] })
useSeoMeta({ title: '상품 수정 · zslab-mall 셀러' })

// 상품 수정(Track 90-C-4·관리자 [id].vue 복제). 상세(90-C-1 GET)를 폼으로 변환해 띄운다. 타 셀러·미존재(404)는 안내 + 목록 이동.
// ?partial=1은 등록 후속(이미지) 단계 실패에서 넘어온 경우(안내 표시). 저장 성공 후에는 상세를 재조회해 폼을 새로 만든다(서버 값 반영·신규 variant id 확보).
// 판매 관리 카드(Track 96-5·D-206·외부 검토 R2 Q9): 판매중지·재판매(확인 다이얼로그)·수동 품절은 **카드 상태(detail)만** 갱신한다 — 폼은 initial을
// 마운트 시 clone하므로 detail 교체는 폼에 닿지 않고, formKey를 올리지 않아 저장 전 수정 내용이 유지된다. 폼 재마운트는 저장 성공(onSaved)·초기 로드만.
const route = useRoute()
const router = useRouter()
const productsApi = useSellerProducts()
const toast = useSellerToast()
const productPublicId = computed<string>(() => String(route.params.id ?? ''))
const backPath = computed(() => resolveBackPath(route.query.back, SELLER_PRODUCTS_PATH))
const partial = computed(() => route.query.partial === '1')

const { data: categoryData } = useCategories()
const categories = computed<CategorySummary[]>(() => categoryData.value ?? [])

const initial = ref<SellerProductForm | null>(null)
const detail = ref<SellerProductDetail | null>(null)
const loading = ref(true)
const notFound = ref(false)
const loadError = ref<string | null>(null)
// 폼 재마운트 키(재조회 후 initial 교체 시 폼 상태를 새로 만든다).
const formKey = ref(0)

async function load(): Promise<void> {
  loading.value = true
  notFound.value = false
  loadError.value = null
  try {
    const response = await productsApi.detail(productPublicId.value)
    detail.value = response
    initial.value = toSellerProductForm(response)
    formKey.value += 1
  } catch (error) {
    if (extractErrorCode(error) === 'PRODUCT_NOT_FOUND') {
      notFound.value = true
    } else {
      loadError.value = toSellerErrorMessage(error)
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function onSaved(): Promise<void> {
  // 검토 반영 ⑥: 저장 성공 후에는 partial 경고가 더 이상 맞지 않으므로 쿼리에서 지운 뒤(재조회 URL 정리) 상세를 다시 읽는다.
  if (partial.value) {
    const query = { ...route.query }
    delete query.partial
    await router.replace({ query })
  }
  await load()
}

function onNotFound(): void {
  toast.danger('상품을 찾을 수 없어 목록으로 돌아갑니다.')
  void navigateTo(backPath.value)
}

// ---------- 판매 관리 카드(폼 보존) ----------
const saleAction = ref<SellerSaleAction | null>(null)

/** 카드 상태만 재조회(폼 무접촉). 404는 초기 로드와 같이 "찾을 수 없음" 화면, 그 외 실패는 토스트만(폼 유지). */
async function refreshCard(): Promise<void> {
  try {
    detail.value = await productsApi.detail(productPublicId.value)
  } catch (error) {
    if (extractErrorCode(error) === 'PRODUCT_NOT_FOUND') {
      notFound.value = true
    } else {
      toast.warning(toSellerErrorMessage(error))
    }
  }
}

function onSaleDone(): void {
  saleAction.value = null
  void refreshCard()
}

function onCardUpdated(updated: SellerProductDetail): void {
  detail.value = updated
}
</script>

<template>
  <div data-testid="seller-product-edit">
    <SellerPageHeader title="상품 수정" :description="detail?.name ?? productPublicId" />
    <v-alert v-if="partial" type="warning" class="mb-4" data-testid="partial-alert">
      상품은 등록되었지만(승인대기) 이미지 저장 단계에서 실패했습니다. 이미지를 확인한 뒤 다시 저장하세요.
    </v-alert>
    <v-card v-if="loading" class="mb-4"><v-card-text><v-skeleton-loader type="article, table" /></v-card-text></v-card>
    <v-card v-else-if="notFound" data-testid="product-not-found">
      <v-card-text class="d-flex flex-column align-center text-center py-12">
        <p class="text-subtitle-1 font-weight-medium mb-1">상품을 찾을 수 없습니다</p>
        <p class="text-body-2 text-medium-emphasis mb-4">내 상품이 아니거나 삭제된 상품입니다: {{ productPublicId }}</p>
        <v-btn color="primary" :to="backPath" data-testid="back-to-list">목록으로</v-btn>
      </v-card-text>
    </v-card>
    <v-alert v-else-if="loadError" type="error" class="mb-4" data-testid="product-load-error">
      {{ loadError }} <v-btn size="small" variant="outlined" color="error" class="ml-2" @click="load">다시 시도</v-btn>
    </v-alert>
    <template v-else-if="initial && detail">
      <SellerProductSaleStatusCard :detail="detail" @sale-action="(action) => (saleAction = action)" @updated="onCardUpdated" @stale="refreshCard" />
      <SellerProductForm
        :key="formKey"
        mode="edit"
        :initial="initial"
        :categories="categories"
        :back-path="backPath"
        @saved="onSaved"
        @not-found="onNotFound"
      />
    </template>

    <SellerProductSaleStatusDialog
      :open="saleAction !== null"
      :action="saleAction ?? 'STOP'"
      :product="detail"
      @done="onSaleDone"
      @stale="onSaleDone"
      @cancel="saleAction = null"
    />
  </div>
</template>
