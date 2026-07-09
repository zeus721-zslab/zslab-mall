<script setup lang="ts">
import type { ProductImage, ProductVariant } from '~/types/product'
import { BUYER_ROLE } from '~/lib/constants/auth'

// 라우트 파라미터(prd_)로 상세를 조회한다. permitAll 공개 카탈로그라 인증 없이 SSR/CSR 모두 조회 가능.
const route = useRoute()
const productPublicId = route.params.productPublicId as string

const auth = useAuthStore()
const cart = useCartStore()

const { data, pending, error, refresh } = useProductDetail(productPublicId)

// 404(PRODUCT_NOT_FOUND)와 그 외 오류를 구분해 안내 문구를 달리한다(존재 은닉이라 미노출도 404).
const errorMessage = computed<string>(() =>
  error.value?.statusCode === 404 ? '상품을 찾을 수 없습니다' : '상품을 불러오지 못했습니다',
)

// 이미지: main 우선·이후 displayOrder 오름차순. BE가 이미 정렬해 주지만 대표(main)를 앞으로 당겨 갤러리 히어로로 쓴다.
const sortedImages = computed<ProductImage[]>(() =>
  [...(data.value?.images ?? [])].sort((first, second) => {
    if (first.main !== second.main) return first.main ? -1 : 1
    return first.displayOrder - second.displayOrder
  }),
)
const activeImageUrl = ref<string | null>(null)
// 데이터 로드/변경 시 히어로 이미지를 대표로 초기화(사용자가 썸네일로 바꾸기 전까지).
watch(
  sortedImages,
  (images) => {
    activeImageUrl.value = images[0]?.imageUrl ?? null
  },
  { immediate: true },
)

// 옵션 선택 상태(그룹명 → 선택값). 단순상품(optionGroups 빈)은 사용하지 않는다.
const selectedOptions = ref<Record<string, string>>({})
function selectOption(groupName: string, value: string): void {
  selectedOptions.value = { ...selectedOptions.value, [groupName]: value }
}

/**
 * 선택된 variant 확정. 단순상품(optionGroups 빈·options 빈)은 단일 variant를 바로 쓴다. 다중 옵션은 전 그룹 선택이
 * 완료됐을 때만 variants에서 options 집합이 정확히 일치하는 variant 1건을 찾는다(미완료·불일치 시 null).
 */
const selectedVariant = computed<ProductVariant | null>(() => {
  const product = data.value
  if (!product) return null
  const groups = product.optionGroups
  if (groups.length === 0) {
    return product.variants[0] ?? null
  }
  const allSelected = groups.every((group) => selectedOptions.value[group.name])
  if (!allSelected) return null
  return (
    product.variants.find(
      (variant) =>
        variant.options.length === groups.length &&
        variant.options.every((option) => selectedOptions.value[option.groupName] === option.value),
    ) ?? null
  )
})

// 표시 가격: variant 확정 시 그 salePrice, 미확정(다중 옵션 선택 전)이면 대표가(displayPrice).
const currentPrice = computed<number>(
  () => selectedVariant.value?.salePrice ?? data.value?.displayPrice ?? 0,
)
const formattedPrice = computed<string>(() => `${currentPrice.value.toLocaleString('ko-KR')}원`)

// 담기 seam(FE-10b 소비): 확정 variant의 대상키. 미확정이면 null.
const selectedVariantPublicId = computed<string | null>(
  () => selectedVariant.value?.variantPublicId ?? null,
)

// 담기 가능 = variant 확정 ∧ 해당 variant 미품절. 상품 단위 품절(soldOut)도 함께 막는다.
const canAddToCart = computed<boolean>(() => {
  const variant = selectedVariant.value
  if (!variant) return false
  return !variant.soldOut && !(data.value?.soldOut ?? false)
})

const quantity = ref<number>(1)
function decrementQuantity(): void {
  if (quantity.value > 1) quantity.value -= 1
}
function incrementQuantity(): void {
  quantity.value += 1
}

// 담기 진행/결과 상태. adding으로 중복 클릭을 막고, 성공/실패 문구를 버튼 아래에 노출한다.
const adding = ref<boolean>(false)
const addSucceeded = ref<boolean>(false)
const addErrorMessage = ref<string>('')

/**
 * 담기 액션(FE-10b 배선). 미인증·비-BUYER면 클릭 시점 인증 게이트(→/login·복귀 경로 전달). 인증이면
 * POST /api/v1/cart/items 후 cart store가 재load해 뱃지를 갱신한다. 페이지 이동은 하지 않는다.
 */
async function handleAddToCart(): Promise<void> {
  if (adding.value) return
  const variantPublicId = selectedVariantPublicId.value
  // 담기 가능(variant 확정·미품절)일 때만 버튼이 활성이나, seam 안전을 위해 대상키 부재는 방어한다.
  if (!variantPublicId) return

  addSucceeded.value = false
  addErrorMessage.value = ''

  // 인증 게이트: 미인증 또는 비-BUYER면 로그인으로 유도(복귀 경로 전달). 페이지 진입은 막지 않고 클릭 시점에만 건다.
  if (!auth.isAuthenticated || auth.role !== BUYER_ROLE) {
    await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }

  adding.value = true
  try {
    await cart.add(variantPublicId, quantity.value)
    addSucceeded.value = true
  } catch (error) {
    // 세션 만료 등으로 서버가 401이면 재로그인 유도, 그 외(403 권한 부족 등)는 안내만 한다.
    const statusCode = (error as { statusCode?: number }).statusCode
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
      return
    }
    addErrorMessage.value = '장바구니에 담지 못했습니다. 잠시 후 다시 시도해 주세요.'
  } finally {
    adding.value = false
  }
}

useSeoMeta({
  title: () => (data.value ? `${data.value.name} · zslab-mall` : '상품 상세 · zslab-mall'),
  description: () => data.value?.description ?? 'zslab-mall 상품 상세',
})
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <!-- Loading -->
      <div v-if="pending" class="grid grid-cols-1 gap-8 md:grid-cols-2">
        <div class="animate-pulse">
          <div class="aspect-square rounded-card bg-gray-100"></div>
        </div>
        <div class="animate-pulse space-y-4">
          <div class="h-4 w-1/4 rounded bg-gray-100"></div>
          <div class="h-7 w-3/4 rounded bg-gray-100"></div>
          <div class="h-5 w-1/3 rounded bg-gray-100"></div>
          <div class="h-8 w-1/2 rounded bg-gray-100"></div>
        </div>
      </div>

      <!-- Error / Not found -->
      <CommonErrorState v-else-if="error || !data" :message="errorMessage" @retry="refresh" />

      <!-- Success -->
      <div v-else class="grid grid-cols-1 gap-8 md:grid-cols-2">
        <!-- 이미지 갤러리 -->
        <div>
          <div class="relative aspect-square overflow-hidden rounded-card border border-line bg-gray-100">
            <img
              v-if="activeImageUrl"
              :src="activeImageUrl"
              :alt="data.name"
              class="h-full w-full object-cover"
            />
            <!-- 이미지 부재 시 회색 placeholder(ProductCard 관습 동형) -->
            <div v-else class="flex h-full w-full items-center justify-center text-gray-300">
              <svg class="h-16 w-16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M6 12h.008v.008H6V12zm18 0a1.5 1.5 0 01-1.5 1.5H3.75A1.5 1.5 0 012.25 12V6A1.5 1.5 0 013.75 4.5h16.5A1.5 1.5 0 0122.5 6v6z" />
              </svg>
            </div>
            <div v-if="data.soldOut" class="absolute inset-0 flex items-center justify-center bg-white/60">
              <span class="rounded-badge bg-badge-soldout-bg px-4 py-1.5 text-base font-medium text-soldout">품절</span>
            </div>
          </div>

          <!-- 썸네일: 이미지 2장 이상일 때만 -->
          <div v-if="sortedImages.length > 1" class="mt-3 flex gap-2 overflow-x-auto">
            <button
              v-for="image in sortedImages"
              :key="image.imageUrl"
              type="button"
              :aria-label="`${data.name} 이미지`"
              class="relative h-16 w-16 shrink-0 overflow-hidden rounded-control border transition duration-normal"
              :class="activeImageUrl === image.imageUrl ? 'border-primary' : 'border-line hover:border-gray-300'"
              @click="activeImageUrl = image.imageUrl"
            >
              <img :src="image.imageUrl" :alt="data.name" class="h-full w-full object-cover" />
            </button>
          </div>
        </div>

        <!-- 기본 정보 + 옵션/variant 선택 + 담기 -->
        <div class="space-y-5">
          <div class="space-y-2">
            <p class="text-sm text-sub">{{ data.categoryName }}</p>
            <h1 class="text-2xl font-medium leading-snug tracking-tight text-ink">{{ data.name }}</h1>
            <p class="text-sm text-seller">{{ data.sellerName }}</p>
          </div>

          <p class="text-3xl font-bold text-price">{{ formattedPrice }}</p>

          <!-- 상세 설명: nullable → 있을 때만 -->
          <p v-if="data.description" class="whitespace-pre-line text-sm leading-relaxed text-ink">
            {{ data.description }}
          </p>

          <!-- 옵션 그룹 선택: 단순상품(optionGroups 빈)은 렌더 생략 -->
          <div v-if="data.optionGroups.length > 0" class="space-y-4">
            <div v-for="group in data.optionGroups" :key="group.name" class="space-y-2">
              <p class="text-sm font-medium text-ink">{{ group.name }}</p>
              <div class="flex flex-wrap gap-2">
                <button
                  v-for="optionValue in group.values"
                  :key="optionValue.value"
                  type="button"
                  class="rounded-control border px-4 py-2 text-sm transition duration-normal focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-gray-900"
                  :class="
                    selectedOptions[group.name] === optionValue.value
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-line text-ink hover:border-gray-300'
                  "
                  @click="selectOption(group.name, optionValue.value)"
                >
                  {{ optionValue.value }}
                </button>
              </div>
            </div>

            <!-- 조합 미완료·불일치 안내(다중 옵션 전용) -->
            <p v-if="!selectedVariant" class="text-sm text-sub">옵션을 모두 선택해 주세요.</p>
            <p v-else-if="selectedVariant.soldOut" class="text-sm text-soldout">선택하신 옵션은 품절입니다.</p>
          </div>

          <!-- 수량 -->
          <div class="flex items-center gap-3">
            <span class="text-sm font-medium text-ink">수량</span>
            <div class="inline-flex items-center rounded-control border border-line">
              <button
                type="button"
                aria-label="수량 감소"
                class="px-3 py-2 text-ink transition duration-normal hover:bg-gray-50 disabled:opacity-40"
                :disabled="quantity <= 1"
                @click="decrementQuantity"
              >
                −
              </button>
              <span class="min-w-10 text-center text-sm font-medium text-ink">{{ quantity }}</span>
              <button
                type="button"
                aria-label="수량 증가"
                class="px-3 py-2 text-ink transition duration-normal hover:bg-gray-50"
                @click="incrementQuantity"
              >
                +
              </button>
            </div>
          </div>

          <!-- 담기: POST /cart/items·인증 게이트·cart store 갱신(FE-10b 배선). 진행 중·미확정·품절이면 비활성. -->
          <Button
            size="lg"
            class="w-full"
            :disabled="!canAddToCart || adding"
            :data-variant-public-id="selectedVariantPublicId ?? undefined"
            @click="handleAddToCart"
          >
            {{ adding ? '담는 중…' : '장바구니 담기' }}
          </Button>

          <!-- 담기 결과: 성공 시 뱃지만 갱신(이동 없음)·실패 시 안내 -->
          <p v-if="addSucceeded" role="status" class="text-sm text-seller">장바구니에 담았습니다.</p>
          <p v-else-if="addErrorMessage" role="alert" class="text-sm text-soldout">{{ addErrorMessage }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
