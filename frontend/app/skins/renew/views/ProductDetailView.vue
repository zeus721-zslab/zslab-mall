<script setup lang="ts">
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'
import MobileActionBar from '../components/MobileActionBar.vue'
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

function formatAmount(value: number): string {
  return value.toLocaleString('ko-KR')
}

// 본문 담기 버튼: 화면에 없을 때만 모바일 고정 바가 나타난다(FE-71 도킹).
const addButton = ref<HTMLButtonElement | null>(null)
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
            <div class="relative aspect-square overflow-hidden rounded-(--panel-radius) bg-(--image-placeholder)">
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

            <hr class="my-6 border-line" />

            <!-- 옵션: 선택 = 칩 보라 채움(aria-pressed) · 품절 표시(흐림·취소선) = 현재 선택 조합 기준 · 비활성 = 이 값으로 살 variant가 전혀 없을 때만(교착 방지·FE-70) -->
            <div v-if="product.optionGroups.length > 0" class="space-y-5">
              <div v-for="group in product.optionGroups" :key="group.name">
                <p class="text-small font-bold text-ink">{{ group.name }}</p>
                <div class="mt-2 flex flex-wrap gap-2">
                  <button
                    v-for="optionValue in group.values"
                    :key="optionValue.value"
                    type="button"
                    :disabled="vm.isOptionValueUnavailable(group.name, optionValue.value)"
                    :aria-pressed="vm.selectedOptions[group.name] === optionValue.value"
                    :class="['chip disabled:cursor-default', vm.isOptionValueSoldOut(group.name, optionValue.value) ? 'line-through opacity-40' : '']"
                    @click="vm.selectOption(group.name, optionValue.value)"
                  >
                    {{ optionValue.value }}<span v-if="vm.isOptionValueSoldOut(group.name, optionValue.value)" class="sr-only"> (품절)</span>
                  </button>
                </div>
              </div>
              <p v-if="!vm.selectedVariant" class="text-small text-sub">옵션을 모두 선택해 주세요.</p>
              <p v-else-if="vm.selectedVariant.soldOut" class="text-small font-bold text-ink">선택하신 옵션은 품절입니다.</p>
            </div>

            <!-- 수량 -->
            <div class="mt-6 flex items-center justify-between gap-4">
              <span class="text-small font-bold text-ink">수량</span>
              <div class="inline-flex items-center rounded-full border border-line bg-white p-1">
                <button
                  type="button"
                  aria-label="수량 감소"
                  class="flex h-11 w-11 items-center justify-center rounded-full text-h3 font-normal text-ink transition duration-fast ease-soft hover:bg-surface-muted disabled:cursor-default disabled:opacity-40 disabled:hover:bg-transparent"
                  :disabled="vm.quantity <= 1"
                  @click="vm.decrementQuantity"
                >
                  −
                </button>
                <span class="min-w-10 text-center text-body font-semibold tabular-nums text-ink">{{ vm.quantity }}</span>
                <button
                  type="button"
                  aria-label="수량 증가"
                  class="flex h-11 w-11 items-center justify-center rounded-full text-h3 font-normal text-ink transition duration-fast ease-soft hover:bg-surface-muted"
                  @click="vm.incrementQuantity"
                >
                  +
                </button>
              </div>
            </div>

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

            <!-- 담기 결과: 성공 카드(이동 없음·장바구니 링크) / 실패 문구 -->
            <Transition v-bind="FADE">
              <div
                v-if="vm.addSucceeded"
                role="status"
                class="mt-4 flex items-center gap-3 rounded-card bg-(--pastel-mint-bg) p-4 text-(--pastel-mint-ink)"
              >
                <span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-white/70" aria-hidden="true">
                  <svg class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M5 12.5l4.5 4.5L19 7.5" />
                  </svg>
                </span>
                <span class="flex-1 text-small font-bold">장바구니에 담았습니다.</span>
                <!-- 띠 면 위 보조 버튼 = 흰 바탕 -->
                <NuxtLink to="/cart" class="btn btn-md shrink-0 bg-white text-primary hover:bg-surface-muted">
                  장바구니 보기
                </NuxtLink>
              </div>
            </Transition>
            <p v-if="!vm.addSucceeded && vm.addErrorMessage" role="alert" class="mt-4 text-small font-bold text-destructive">{{ vm.addErrorMessage }}</p>
          </div>
        </div>

        <!-- 상품 설명: 전체 폭 흰 카드 · 본문 최대 880px -->
        <section v-if="product.description" class="mt-20 rounded-card bg-white px-5 py-10 shadow-e1 md:px-10 md:py-14">
          <div class="mx-auto max-w-[880px]">
            <SectionHeading tag="Details" title="상품 설명" />
            <p class="whitespace-pre-line text-body text-ink">{{ product.description }}</p>
          </div>
        </section>

        <!-- 셀러의 다른 상품: 조회 실패·0개면 숨김 -->
        <section v-if="vm.sellerProducts.length > 0" class="mt-20">
          <SectionHeading tag="More from seller" :title="`${product.sellerName}의 다른 상품`" />
          <div :class="PRODUCT_GRID">
            <RenewProductCard v-for="item in vm.sellerProducts" :key="item.productPublicId" :product="item" />
          </div>
        </section>

        <!-- <1024 하단 고정 바: 본문 담기 버튼이 화면에 없을 때만·같은 함수·같은 비활성 규칙 -->
        <MobileActionBar
          :anchor="addButton"
          label="총 상품 금액"
          :amount="vm.totalPrice"
          :pending-text="totalPendingText"
          :button-label="addButtonLabel"
          :disabled="!vm.canAddToCart || vm.adding"
          @action="vm.handleAddToCart"
        />
      </template>
    </div>
  </div>
</template>
