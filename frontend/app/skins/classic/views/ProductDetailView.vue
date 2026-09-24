<script setup lang="ts">
import type { ProductDetailPageVm } from '~/skins/contracts/product-detail'

defineProps<{ vm: ProductDetailPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <!-- Loading -->
      <div v-if="vm.pending" class="grid grid-cols-1 gap-8 md:grid-cols-2">
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
      <CommonErrorState v-else-if="vm.error || !vm.data" :message="vm.errorMessage" @retry="vm.refresh" />

      <!-- Success -->
      <div v-else class="grid grid-cols-1 gap-8 md:grid-cols-2">
        <!-- 이미지 갤러리 -->
        <div>
          <div class="relative aspect-square overflow-hidden rounded-card border border-line bg-gray-100">
            <img
              v-if="vm.activeImageUrl"
              :src="vm.activeImageUrl"
              :alt="vm.data.name"
              class="h-full w-full object-cover"
            />
            <!-- 이미지 부재 시 회색 placeholder(ProductCard 관습 동형) -->
            <div v-else class="flex h-full w-full items-center justify-center text-gray-300">
              <svg class="h-16 w-16" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M6 12h.008v.008H6V12zm18 0a1.5 1.5 0 01-1.5 1.5H3.75A1.5 1.5 0 012.25 12V6A1.5 1.5 0 013.75 4.5h16.5A1.5 1.5 0 0122.5 6v6z" />
              </svg>
            </div>
            <!-- 판매 불가 오버레이(품절 배지 재사용·FE-18 판매중지 라벨 우선) -->
            <div v-if="vm.unavailableLabel" class="absolute inset-0 flex items-center justify-center bg-white/60">
              <span class="rounded-badge bg-badge-soldout-bg px-4 py-1.5 text-base font-medium text-soldout">{{ vm.unavailableLabel }}</span>
            </div>
          </div>

          <!-- 썸네일: 이미지 2장 이상일 때만 -->
          <div v-if="vm.sortedImages.length > 1" class="mt-3 flex gap-2 overflow-x-auto">
            <button
              v-for="image in vm.sortedImages"
              :key="image.imageUrl"
              type="button"
              :aria-label="`${vm.data.name} 이미지`"
              class="relative h-16 w-16 shrink-0 overflow-hidden rounded-control border transition duration-normal"
              :class="vm.activeImageUrl === image.imageUrl ? 'border-primary' : 'border-line hover:border-gray-300'"
              @click="vm.activeImageUrl = image.imageUrl"
            >
              <img :src="image.imageUrl" :alt="vm.data.name" class="h-full w-full object-cover" />
            </button>
          </div>
        </div>

        <!-- 기본 정보 + 옵션/variant 선택 + 담기 -->
        <div class="space-y-5">
          <div class="space-y-2">
            <p class="text-sm text-sub">{{ vm.data.categoryName }}</p>
            <h1 class="text-2xl font-medium leading-snug tracking-tight text-ink">{{ vm.data.name }}</h1>
            <p class="text-sm text-seller">{{ vm.data.sellerName }}</p>
          </div>

          <p class="text-3xl font-bold text-price">{{ vm.formattedPrice }}</p>

          <!-- 상세 설명: nullable → 있을 때만 -->
          <p v-if="vm.data.description" class="whitespace-pre-line text-sm leading-relaxed text-ink">
            {{ vm.data.description }}
          </p>

          <!-- 옵션 그룹 선택: 단순상품(optionGroups 빈)은 렌더 생략 -->
          <div v-if="vm.data.optionGroups.length > 0" class="space-y-4">
            <div v-for="group in vm.data.optionGroups" :key="group.name" class="space-y-2">
              <p class="text-sm font-medium text-ink">{{ group.name }}</p>
              <div class="flex flex-wrap gap-2">
                <button
                  v-for="optionValue in group.values"
                  :key="optionValue.value"
                  type="button"
                  class="rounded-control border px-4 py-2 text-sm transition duration-normal focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-gray-900"
                  :class="
                    vm.selectedOptions[group.name] === optionValue.value
                      ? 'border-primary bg-primary text-primary-foreground'
                      : 'border-line text-ink hover:border-gray-300'
                  "
                  @click="vm.selectOption(group.name, optionValue.value)"
                >
                  {{ optionValue.value }}
                </button>
              </div>
            </div>

            <!-- 조합 미완료·불일치 안내(다중 옵션 전용) -->
            <p v-if="!vm.selectedVariant" class="text-sm text-sub">옵션을 모두 선택해 주세요.</p>
            <p v-else-if="vm.selectedVariant.soldOut" class="text-sm text-soldout">선택하신 옵션은 품절입니다.</p>
          </div>

          <!-- 수량 -->
          <div class="flex items-center gap-3">
            <span class="text-sm font-medium text-ink">수량</span>
            <div class="inline-flex items-center rounded-control border border-line">
              <button
                type="button"
                aria-label="수량 감소"
                class="px-3 py-2 text-ink transition duration-normal hover:bg-gray-50 disabled:opacity-40"
                :disabled="vm.quantity <= 1"
                @click="vm.decrementQuantity"
              >
                −
              </button>
              <span class="min-w-10 text-center text-sm font-medium text-ink">{{ vm.quantity }}</span>
              <button
                type="button"
                aria-label="수량 증가"
                class="px-3 py-2 text-ink transition duration-normal hover:bg-gray-50"
                @click="vm.incrementQuantity"
              >
                +
              </button>
            </div>
          </div>

          <!-- 담기: POST /cart/items·인증 게이트·cart store 갱신(FE-10b 배선). 진행 중·미확정·품절·판매중지면 비활성. -->
          <Button
            size="lg"
            class="w-full"
            :disabled="!vm.canAddToCart || vm.adding"
            :data-variant-public-id="vm.selectedVariantPublicId ?? undefined"
            @click="vm.handleAddToCart"
          >
            {{ vm.adding ? '담는 중…' : '장바구니 담기' }}
          </Button>

          <!-- 담기 결과: 성공 시 뱃지만 갱신(이동 없음)·실패 시 안내 -->
          <p v-if="vm.addSucceeded" role="status" class="text-sm text-seller">장바구니에 담았습니다.</p>
          <p v-else-if="vm.addErrorMessage" role="alert" class="text-sm text-soldout">{{ vm.addErrorMessage }}</p>
        </div>
      </div>
    </div>
  </div>
</template>
