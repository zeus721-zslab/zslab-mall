<script setup lang="ts">
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'
import { PRODUCT_SECTION_IDS } from '~/lib/constants/product-sections'
import CartAddedSnackbar from '../components/CartAddedSnackbar.vue'
import { flyToCart } from '../fly-to-cart'
import MobileActionBar from '../components/MobileActionBar.vue'
import ProductOptionSheet from '../components/ProductOptionSheet.vue'
import ProductPurchaseOptions from '../components/ProductPurchaseOptions.vue'
import ProductGlanceChips from '../components/ProductGlanceChips.vue'
import ProductQuestionSection from '../components/ProductQuestionSection.vue'
import ProductReviewSection from '../components/ProductReviewSection.vue'
import ProductSectionNav from '../components/ProductSectionNav.vue'
import type { ProductSectionNavItem } from '~/skins/contracts/product-section-nav'
import RenewBadge from '../components/RenewBadge.vue'
import RenewProductCard from '../components/RenewProductCard.vue'
import SectionHeading from '../components/SectionHeading.vue'

// renew 상품 상세(FE-70). ≥1024 = 왼쪽 갤러리 · 오른쪽 구매 영역(sticky), 그 아래 상품 설명 · 셀러의 다른 상품.
// <1024 = 구매 영역이 이미지 아래로 이어지고 화면 하단 고정 바(총 금액 + 담기)가 붙는다(FE-78 — 768~1023 포함). 담기·옵션·수량은 모두 페이지 함수를 쓴다.
const props = defineProps<{ vm: ProductDetailPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
// 상품 열 수는 메인·목록과 같다: ≥1280 5 · ≥1024 4 · ≥768 3 · 미만 2.
const PRODUCT_GRID = 'grid grid-cols-2 gap-x-4 gap-y-10 md:grid-cols-3 md:gap-x-6 lg:grid-cols-4 xl:grid-cols-5'
// 옵션 확정 전에는 합계를 만들지 않는다(금액 박스·고정 바 같은 문구).
const TOTAL_PENDING_TEXT = '옵션을 선택해 주세요'
const FADE = {
  enterActiveClass: 'transition-opacity duration-fast ease-soft motion-reduce:transition-none',
  leaveActiveClass: 'transition-opacity duration-fast ease-soft motion-reduce:transition-none',
  enterFromClass: 'opacity-0',
  leaveToClass: 'opacity-0',
}

const product = computed(() => props.vm.data)
const addButtonLabel = computed(() => (props.vm.adding ? '담는 중…' : '장바구니 담기'))
// 금액이 없을 때 문구: 판매 불가(판매 가능 variant 없음 포함)면 그 사유, 아니면 옵션 선택 안내.
const totalPendingText = computed(() => props.vm.unavailableLabel ?? TOTAL_PENDING_TEXT)

// 섹션 바 알약: 화면에 있는 섹션만(설명 없는 상품 · 스킨이 리뷰·묻기를 선언하지 않은 경우 제외). 건수는 리뷰 요약·질문 목록 총수.
// 첫 페이지 응답 전·조회 실패 시에는 기본값 0을 보이지 않게 건수 없이 이름만 둔다(섹션의 pending·failed 판정과 같음 · FE-111 · UX-02).
const sectionNavItems = computed<ProductSectionNavItem[]>(() => {
  const items: ProductSectionNavItem[] = []
  if (product.value?.description) items.push({ id: PRODUCT_SECTION_IDS.description, label: '상품설명' })
  const reviews = props.vm.reviews
  if (reviews) items.push({ id: PRODUCT_SECTION_IDS.reviews, label: reviews.pending || reviews.failed ? '리뷰' : `리뷰 ${(reviews.summary?.reviewCount ?? reviews.totalCount).toLocaleString('ko-KR')}` })
  const questions = props.vm.questions
  if (questions) items.push({ id: PRODUCT_SECTION_IDS.questions, label: questions.pending || questions.failed ? 'Q&A' : `Q&A ${questions.totalCount.toLocaleString('ko-KR')}` })
  return items
})

function formatAmount(value: number): string {
  return value.toLocaleString('ko-KR')
}

// 본문 담기 버튼: 화면에 없을 때만 모바일 고정 바가 나타난다(FE-71 도킹).
const addButton = ref<HTMLButtonElement | null>(null)

// 옵션 상품의 하단 바 → 옵션 시트(FE-100). 열림 여부만 화면 상태이고 옵션·수량·담기·바로구매는 페이지 vm이다.
const hasOptions = computed(() => (product.value?.optionGroups.length ?? 0) > 0)
const optionSheetOpen = ref<boolean>(false)
// 시트를 먼저 닫고 담는다 — 담기 성공 스낵바·비행이 시트 뒤에 가리지 않게 한다.
function addFromSheet(): void {
  optionSheetOpen.value = false
  props.vm.handleAddToCart()
}
function buyFromSheet(): void {
  optionSheetOpen.value = false
  props.vm.handleBuyNow()
}

// 담기 성공마다 대표 이미지가 헤더 장바구니로 날아간다(FE-99). 대표 img는 썸네일 전환 때 바뀌므로 감싼 박스에서 찾는다.
// flush post: 담기 뒤 장바구니 재조회로 헤더 뱃지가 새로 그려진 다음에 목적지를 잰다.
const galleryImageBox = ref<HTMLElement | null>(null)
watch(
  () => props.vm.addedSignal,
  () => flyToCart(galleryImageBox.value?.querySelector('img') ?? null),
  { flush: 'post' },
)
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <!-- 로딩 -->
      <div v-if="vm.pending" class="lg:grid lg:grid-cols-2 lg:gap-12 xl:gap-16" aria-hidden="true">
        <div class="aspect-square rounded-(--panel-radius) bg-(--image-placeholder)"></div>
        <div class="mt-8 space-y-4 lg:mt-0">
          <div class="h-9 w-24 rounded-full bg-surface-muted"></div>
          <div class="h-10 w-3/4 rounded-full bg-surface-muted"></div>
          <div class="h-8 w-40 rounded-full bg-surface-muted"></div>
          <div class="h-10 w-1/3 rounded-full bg-surface-muted"></div>
        </div>
      </div>

      <!-- 오류·없음 -->
      <CommonErrorState v-else-if="vm.error || !product" :message="vm.errorMessage" @retry="vm.refresh" />

      <template v-else>
        <div class="lg:grid lg:grid-cols-2 lg:items-start lg:gap-12 xl:gap-16">
          <!-- 갤러리 -->
          <div>
            <div ref="galleryImageBox" class="relative aspect-square overflow-hidden rounded-(--panel-radius) bg-(--image-placeholder)">
              <!-- 썸네일 전환 시 대표 이미지 페이드(겹쳐서 교차). 대표 이미지가 모바일 LCP라 즉시·높은 우선순위로 요청한다(FE-85). -->
              <Transition v-bind="FADE">
                <img
                  v-if="vm.activeImageUrl"
                  :key="vm.activeImageUrl"
                  :src="vm.activeImageUrl"
                  :alt="product.name"
                  loading="eager"
                  fetchpriority="high"
                  class="absolute inset-0 h-full w-full object-cover"
                />
              </Transition>
              <!-- 판매 불가(판매중지 우선·품절) -->
              <div v-if="vm.unavailableLabel" class="absolute inset-0 flex items-center justify-center bg-white/60">
                <RenewBadge tone="neutral">{{ vm.unavailableLabel }}</RenewBadge>
              </div>
            </div>

            <!-- 썸네일: 이미지 2장 이상일 때만 -->
            <div v-if="vm.sortedImages.length > 1" class="-mx-1 mt-4 flex gap-3 overflow-x-auto px-1 py-1 scrollbar-none">
              <button
                v-for="(image, index) in vm.sortedImages"
                :key="image.imageUrl"
                type="button"
                :aria-label="`${product.name} 이미지 ${index + 1}`"
                :aria-pressed="vm.activeImageUrl === image.imageUrl"
                :class="[
                  'h-[88px] w-[88px] shrink-0 overflow-hidden rounded-[18px] border-2 bg-(--image-placeholder) transition duration-fast ease-soft focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary',
                  vm.activeImageUrl === image.imageUrl ? 'border-ink' : 'border-transparent hover:border-line',
                ]"
                @click="vm.activeImageUrl = image.imageUrl"
              >
                <img :src="image.imageUrl" :alt="product.name" class="h-full w-full object-cover" />
              </button>
            </div>
          </div>

          <!-- 구매 영역: ≥1024 헤더 아래 24px에 고정 -->
          <div class="mt-8 lg:sticky lg:top-[calc(var(--header-height)_+_24px)] lg:mt-0">
            <NuxtLink
              v-if="product.categoryName"
              :to="`/categories/${product.categoryId}`"
              class="btn btn-secondary btn-sm max-md:min-h-11"
            >
              {{ product.categoryName }}
            </NuxtLink>

            <h1 class="mt-4 text-h1 text-ink" data-testid="product-detail-name">{{ product.name }}</h1>

            <!-- 셀러는 눌리지 않으므로 알약 모양 없이 평문으로 둔다(FE-82). -->
            <p class="mt-2 text-small text-sub" data-testid="product-detail-seller">{{ product.sellerName }}</p>

            <p class="mt-6 text-h1 font-semibold tabular-nums text-ink" data-testid="product-detail-price">{{ vm.formattedPrice }}</p>

            <!-- 한눈에 칩(Track 106-2): 리뷰·Q&A를 구매 결정 지점에서 바로 보이게 한다. -->
            <ProductGlanceChips :reviews="vm.reviews" :questions="vm.questions" :nav="vm.sectionNav" />

            <hr class="my-6 border-line" />

            <!-- 옵션·수량: 옵션 시트와 같은 컴포넌트·같은 vm(FE-100) -->
            <ProductPurchaseOptions :vm="vm" :product="product" />

            <!-- 총 상품 금액: 흰 콘텐츠 카드 + 그림자 1 -->
            <div class="mt-6 rounded-card bg-white p-5 shadow-e1">
              <div class="flex items-baseline justify-between gap-4">
                <span class="text-small font-bold text-ink">총 상품 금액</span>
                <span v-if="vm.totalPrice !== null" class="text-ink">
                  <span class="text-h2 font-semibold tabular-nums">{{ formatAmount(vm.totalPrice) }}</span><span class="ml-0.5 text-body">원</span>
                </span>
                <span v-else class="text-small text-sub">{{ totalPendingText }}</span>
              </div>
              <p class="mt-1 text-right text-caption font-normal text-sub">배송비 무료</p>
            </div>

            <!-- 담기(이 영역의 주 버튼): 진행 중·미확정·품절·판매중지면 비활성(canAddToCart) -->
            <button
              ref="addButton"
              type="button"
              class="btn btn-primary btn-lg mt-4 w-full"
              :disabled="!vm.canAddToCart || vm.adding"
              :data-variant-public-id="vm.selectedVariantPublicId ?? undefined"
              @click="vm.handleAddToCart"
            >
              {{ addButtonLabel }}
            </button>

            <!-- 담기 실패 문구. 성공은 화면 하단 스낵바(FE-99). -->
            <p v-if="vm.addErrorMessage" role="alert" class="mt-4 text-small font-bold text-destructive">{{ vm.addErrorMessage }}</p>
          </div>
        </div>

        <!-- 진행형 섹션 바(Track 106-2): 구매 영역 아래 · 상품 설명 위 · 스크롤하면 헤더 아래에 붙는다. -->
        <ProductSectionNav v-if="sectionNavItems.length > 0" :nav="vm.sectionNav" :items="sectionNavItems" :questions="vm.questions" />

        <!-- 상품 설명: 전체 폭 흰 카드 · 본문 최대 880px -->
        <section
          v-if="product.description"
          :id="PRODUCT_SECTION_IDS.description"
          class="mt-6 scroll-mt-(--product-section-offset) rounded-card bg-white px-5 py-10 shadow-e1 md:px-10 md:py-14"
        >
          <div class="mx-auto max-w-[880px]">
            <SectionHeading tag="Details" title="상품 설명" />
            <p class="whitespace-pre-line text-body text-ink">{{ product.description }}</p>
          </div>
        </section>

        <!-- 리뷰(Track 106-1): 설명 뒤 · 셀러 상품 앞. 스킨이 productReviews를 선언했을 때만 vm.reviews가 있다. -->
        <ProductReviewSection v-if="vm.reviews" :reviews="vm.reviews" />

        <!-- 묻기(Track 106-2): 리뷰 뒤 · 셀러 상품 앞. 스킨이 productQuestions를 선언했을 때만 vm.questions가 있다. -->
        <ProductQuestionSection v-if="vm.questions" :questions="vm.questions" />

        <!-- 셀러의 다른 상품: 조회 실패·0개면 숨김 -->
        <section v-if="vm.sellerProducts.length > 0" class="mt-20">
          <SectionHeading tag="More from seller" :title="`${product.sellerName}의 다른 상품`" />
          <div :class="PRODUCT_GRID">
            <RenewProductCard v-for="item in vm.sellerProducts" :key="item.productPublicId" :product="item" />
          </div>
        </section>

        <!-- <1024 하단 고정 바: 본문 담기 버튼이 화면에 없을 때만. 단일 옵션 상품 = 같은 담기 함수·같은 비활성 규칙(D2 β) /
             옵션 상품 = [장바구니 담기][바로구매] 모두 옵션 시트를 연다(FE-100 · 판매 불가·담는 중이면 비활성). -->
        <MobileActionBar
          v-if="hasOptions"
          :anchor="addButton"
          label="총 상품 금액"
          :amount="vm.totalPrice"
          :pending-text="totalPendingText"
          secondary-label="장바구니 담기"
          button-label="바로구매"
          :disabled="vm.unavailableLabel !== null || vm.adding"
          @secondary="optionSheetOpen = true"
          @action="optionSheetOpen = true"
        />
        <MobileActionBar
          v-else
          :anchor="addButton"
          label="총 상품 금액"
          :amount="vm.totalPrice"
          :pending-text="totalPendingText"
          :button-label="addButtonLabel"
          :disabled="!vm.canAddToCart || vm.adding"
          @action="vm.handleAddToCart"
        />
        <ProductOptionSheet v-if="hasOptions" v-model:open="optionSheetOpen" :vm="vm" :product="product" @add="addFromSheet" @buy="buyFromSheet" />

        <!-- 담기 성공 스낵바: 하단 바가 보이면 바 바로 위, 아니면 화면 하단 -->
        <CartAddedSnackbar :signal="vm.addedSignal" />
      </template>
    </div>
  </div>
</template>
