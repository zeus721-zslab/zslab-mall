<script setup lang="ts">
import type { ProductImage, ProductSummary, ProductVariant } from '~/types/product'
import { BUYER_ROLE } from '~/lib/constants/auth'
import { isOptionValueSoldOut } from '~/lib/utils/product-option-availability'
import { buyNowCheckoutPath } from '~/lib/utils/buy-now'
import { CART_ITEM_QUANTITY_MAX, CART_QUANTITY_LIMIT_NOTICE, cartQuantityErrorMessage, clampCartQuantity } from '~/lib/constants/cart'
import type { CartQuantityErrorLike } from '~/lib/constants/cart'
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'

// 라우트 파라미터(prd_)로 상세를 조회한다. permitAll 공개 카탈로그라 인증 없이 SSR/CSR 모두 조회 가능.
const route = useRoute()
const productPublicId = route.params.productPublicId as string

const auth = useAuthStore()
const cart = useCartStore()

const productDetail = useProductDetail(productPublicId)
const { data, pending, error, refresh } = productDetail

// 셀러의 다른 상품은 스킨이 productDetailMore를 선언했을 때만 조회한다(FE-70). classic은 추가 조회 0건.
const detailMore = useSkinNeeds('productDetailMore') ? useProductDetailMore(productPublicId, productDetail) : undefined
const sellerProducts = computed<ProductSummary[]>(() => detailMore?.sellerProducts.value ?? [])

// 리뷰 요약·목록은 스킨이 productReviews를 선언했을 때만 조회한다(Track 106-1 · 요약·첫 페이지 SSR).
const reviews = useSkinNeeds('productReviews') ? useProductReviews(productPublicId, productDetail) : null
// 묻기·즉시 답·질문 목록은 스킨이 productQuestions를 선언했을 때만 조회한다(Track 106-2 · 목록 첫 페이지 SSR).
const questions = useSkinNeeds('productQuestions') ? useProductQuestions(productPublicId) : null
// 한눈에 칩·진행형 섹션 바(Track 106-2): 섹션 이동·활성·진행률. 묻기 입력 상태는 위 questions 인스턴스 하나를 섹션과 바가 함께 쓴다.
const sectionNav = useProductSectionNav()

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
// 히어로 이미지: 사용자가 고른 썸네일이 없으면 대표(첫 이미지). 뷰가 썸네일 클릭으로 바꾼다(setter).
// ref + watch(immediate)로 채우면 SSR setup 시점엔 조회 전이라 null로 굳고, 조회 뒤 watch는 SSR에서 돌지 않아
// 대표 img가 SSR HTML에 빠진다(FE-85). computed는 렌더 시점 데이터로 계산돼 SSR에도 나온다.
const selectedImageUrl = ref<string | null>(null)
// 데이터 로드/변경 시 사용자가 고른 썸네일을 비워 대표로 되돌린다.
watch(sortedImages, () => {
  selectedImageUrl.value = null
})
const activeImageUrl = computed<string | null>({
  get: () => selectedImageUrl.value ?? sortedImages.value[0]?.imageUrl ?? null,
  set: (imageUrl) => {
    selectedImageUrl.value = imageUrl
  },
})

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

// 옵션 값 품절(FE-70): 표시 = 현재 선택 조합 기준 / 비활성 = 다른 선택과 무관하게 이 값으로 살 variant가 없을 때만.
// 조합 기준으로 비활성까지 걸면 2그룹 대각 품절에서 구매 가능한 조합으로 옮겨갈 수 없는 교착이 생긴다.
function optionValueSoldOut(groupName: string, value: string): boolean {
  return isOptionValueSoldOut(data.value?.variants ?? [], selectedOptions.value, groupName, value)
}
function optionValueUnavailable(groupName: string, value: string): boolean {
  return isOptionValueSoldOut(data.value?.variants ?? [], {}, groupName, value)
}

// 담기 seam(FE-10b 소비): 확정 variant의 대상키. 미확정이면 null.
const selectedVariantPublicId = computed<string | null>(
  () => selectedVariant.value?.variantPublicId ?? null,
)

// 상품 단위 판매 불가 표기(FE-18). 판매중지가 품절보다 우선한다(둘 다 해당 시 판매중지만 표기). 분기 2개라 순수 함수 분리 없이 computed로 둔다.
const unavailableLabel = computed<string | null>(() => {
  if (data.value?.saleStopped) return '판매가 중지된 상품입니다'
  if (data.value?.soldOut) return '품절'
  return null
})

// 담기 가능 = variant 확정 ∧ 해당 variant 미품절 ∧ 상품 단위 판매 불가(품절·판매중지) 아님.
const canAddToCart = computed<boolean>(() => {
  const variant = selectedVariant.value
  if (!variant) return false
  return !variant.soldOut && unavailableLabel.value === null
})

const quantity = ref<number>(1)
// 상한(P-08 · BE 999) 초과 시도 안내. 수량을 줄이면 지운다.
const quantityNotice = ref<string>('')
function decrementQuantity(): void {
  if (quantity.value > 1) quantity.value -= 1
  quantityNotice.value = ''
}
function incrementQuantity(): void {
  const requested = quantity.value + 1
  quantity.value = clampCartQuantity(requested)
  quantityNotice.value = requested > CART_ITEM_QUANTITY_MAX ? CART_QUANTITY_LIMIT_NOTICE : ''
}

// 총 상품 금액: 장바구니와 같은 단가(= 확정 variant salePrice = basePrice + additionalPrice) × 수량. 확정 전에는 대표가(최저가)로
// 합계를 만들지 않도록 null(FE-70).
const totalPrice = computed<number | null>(() =>
  selectedVariant.value ? currentPrice.value * quantity.value : null,
)

// 담기 진행/결과 상태. adding으로 중복 클릭을 막고, 실패 문구는 버튼 아래에 노출한다.
// addedSignal: 성공할 때마다 1씩 올리는 신호(FE-99). 같은 상품을 연달아 담아도 매번 새 값이라 스낵바·비행·뱃지가 다시 동작한다.
const adding = ref<boolean>(false)
const addedSignal = ref<number>(0)
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

  addErrorMessage.value = ''

  // 인증 게이트: 미인증 또는 비-BUYER면 로그인으로 유도(복귀 경로 전달). 페이지 진입은 막지 않고 클릭 시점에만 건다.
  if (!auth.isAuthenticated || auth.role !== BUYER_ROLE) {
    await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
    return
  }

  adding.value = true
  try {
    await cart.add(variantPublicId, quantity.value)
    addedSignal.value += 1
  } catch (error) {
    // 세션 만료 등으로 서버가 401이면 재로그인 유도, 구매 불가(422·Track 71)는 전용 문구, 그 외(403 권한 부족 등)는 안내만 한다.
    const statusCode = (error as { statusCode?: number }).statusCode
    const code = (error as { data?: { code?: string } }).data?.code
    if (statusCode === 401) {
      await navigateTo(`/login?redirect=${encodeURIComponent(route.fullPath)}`)
      return
    }
    if (statusCode === 422 && code === 'CART_ITEM_NOT_PURCHASABLE') {
      addErrorMessage.value = '지금 구매할 수 없는 상품입니다.'
      return
    }
    const quantityMessage = cartQuantityErrorMessage(error as CartQuantityErrorLike)
    if (quantityMessage !== null) {
      addErrorMessage.value = quantityMessage
      return
    }
    addErrorMessage.value = '장바구니에 담지 못했습니다. 잠시 후 다시 시도해 주세요.'
  } finally {
    adding.value = false
  }
}

/**
 * 바로구매(FE-100 · D1 β): 확정 variant·수량을 URL에 실어 주문서 단일 상품 경로로 간다. 장바구니 API는 부르지 않는다.
 * 로그인 판단은 주문서의 buyer 미들웨어가 한다(로그인 뒤 같은 주문서 URL로 복귀).
 */
async function handleBuyNow(): Promise<void> {
  const variantPublicId = selectedVariantPublicId.value
  if (!variantPublicId || !canAddToCart.value) return
  await navigateTo(buyNowCheckoutPath({ productPublicId, variantPublicId, quantity: quantity.value }))
}

useSeoMeta({
  title: () => (data.value ? `${data.value.name} · zslab-mall` : '상품 상세 · zslab-mall'),
  description: () => data.value?.description ?? 'zslab-mall 상품 상세',
})

const vm: ProductDetailPageVm = reactive({
  pending,
  error,
  data,
  refresh,
  errorMessage,
  sortedImages,
  activeImageUrl,
  unavailableLabel,
  formattedPrice,
  selectedOptions,
  selectOption,
  selectedVariant,
  selectedVariantPublicId,
  quantity,
  decrementQuantity,
  incrementQuantity,
  quantityNotice,
  canAddToCart,
  adding,
  addedSignal,
  addErrorMessage,
  handleAddToCart,
  handleBuyNow,
  isOptionValueSoldOut: optionValueSoldOut,
  isOptionValueUnavailable: optionValueUnavailable,
  totalPrice,
  sellerProducts,
  reviews,
  questions,
  sectionNav,
})
</script>

<template>
  <component :is="useSkinView('ProductDetailView')" :vm="vm" />
</template>
