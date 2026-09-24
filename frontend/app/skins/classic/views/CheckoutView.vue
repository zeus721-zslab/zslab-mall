<script setup lang="ts">
import type { CheckoutPageVm } from '~/skins/contracts/checkout'

defineProps<{ vm: CheckoutPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[1240px] px-4 md:px-6">
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">주문/결제</h1>

      <form class="grid grid-cols-1 gap-8 lg:grid-cols-[1fr_320px]" @submit.prevent="vm.handleSubmit">
        <!-- 주문 상품 + 배송지 + 결제수단 -->
        <div class="space-y-8">
          <!-- 주문 상품 목록(FE-17). 로드 실패 시 재시도, 선택 0개면 목록 생략(안내는 결제 영역) -->
          <CommonErrorState v-if="vm.cartError" message="주문 상품을 불러오지 못했습니다" @retry="vm.refreshCart" />
          <CheckoutOrderItemList v-else-if="vm.summary.items.length > 0" :items="vm.summary.items" />

          <!-- 배송지 -->
          <section class="space-y-4">
            <h2 class="text-lg font-semibold text-ink">배송지</h2>

            <!-- 저장된 배송지 불러오기(FE-16). 0건·로드 실패 시 드롭다운 숨김·수기 입력. -->
            <p v-if="vm.addressLoadFailed" role="status" class="text-sm text-sub">
              저장된 배송지를 불러오지 못했습니다. 배송지를 직접 입력해 주세요.
            </p>
            <div v-if="vm.hasAddresses" class="space-y-1.5">
              <label for="savedAddress" class="block text-sm font-medium text-ink">저장된 배송지</label>
              <select
                id="savedAddress"
                v-model="vm.selectedKey"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                @change="vm.onSelectAddress"
              >
                <option v-for="address in vm.addressList" :key="address.id" :value="String(address.id)">
                  {{ address.recipientName }} · {{ address.addressRoad }}<template v-if="address.isDefault"> (기본)</template>
                </option>
                <option value="new">새 주소 입력</option>
              </select>
            </div>

            <div class="space-y-1.5">
              <label for="recipientName" class="block text-sm font-medium text-ink">받는 사람 <span class="text-soldout">*</span></label>
              <input
                id="recipientName"
                v-model="vm.recipientName"
                type="text"
                autocomplete="name"
                required
                :maxlength="vm.RECIPIENT_NAME_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="받는 사람 이름"
              />
            </div>

            <div class="space-y-1.5">
              <label for="recipientPhone" class="block text-sm font-medium text-ink">연락처 <span class="text-soldout">*</span></label>
              <input
                id="recipientPhone"
                v-model="vm.recipientPhone"
                type="tel"
                autocomplete="tel"
                required
                :maxlength="vm.RECIPIENT_PHONE_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="010-0000-0000"
              />
            </div>

            <div class="space-y-1.5">
              <label for="zonecode" class="block text-sm font-medium text-ink">우편번호 <span class="text-soldout">*</span></label>
              <input
                id="zonecode"
                v-model="vm.zonecode"
                type="text"
                autocomplete="postal-code"
                required
                :maxlength="vm.ZONECODE_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="우편번호"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressRoad" class="block text-sm font-medium text-ink">도로명 주소 <span class="text-soldout">*</span></label>
              <input
                id="addressRoad"
                v-model="vm.addressRoad"
                type="text"
                autocomplete="address-line1"
                required
                :maxlength="vm.ADDRESS_ROAD_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="도로명 주소"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressJibun" class="block text-sm font-medium text-ink">지번 주소</label>
              <input
                id="addressJibun"
                v-model="vm.addressJibun"
                type="text"
                :maxlength="vm.ADDRESS_JIBUN_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="지번 주소 (선택)"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressDetail" class="block text-sm font-medium text-ink">상세 주소</label>
              <input
                id="addressDetail"
                v-model="vm.addressDetail"
                type="text"
                autocomplete="address-line2"
                :maxlength="vm.ADDRESS_DETAIL_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="상세 주소 (선택)"
              />
            </div>

            <div class="space-y-1.5">
              <label for="deliveryMemo" class="block text-sm font-medium text-ink">배송 메모</label>
              <input
                id="deliveryMemo"
                v-model="vm.deliveryMemo"
                type="text"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="배송 시 요청사항 (선택)"
              />
            </div>

            <!-- 배송지 저장(FE-16): 새 주소이거나 불러온 주소를 수정했을 때만 노출·기본 체크. -->
            <label v-if="vm.showSaveCheckbox" class="flex items-center gap-2 text-sm text-ink">
              <input v-model="vm.saveAddress" type="checkbox" class="h-4 w-4 rounded border-line" />
              이 배송지를 주소록에 저장
            </label>
          </section>

          <!-- 결제수단 -->
          <section class="space-y-4">
            <h2 class="text-lg font-semibold text-ink">결제수단</h2>
            <div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <label
                v-for="option in vm.PAYMENT_METHODS"
                :key="option.value"
                class="flex cursor-pointer items-center justify-center gap-2 rounded-control border px-4 py-2.5 text-sm font-medium transition duration-normal"
                :class="vm.method === option.value ? 'border-primary text-primary' : 'border-line text-sub hover:bg-gray-50'"
              >
                <input v-model="vm.method" type="radio" name="method" :value="option.value" class="sr-only" />
                {{ option.label }}
              </label>
            </div>
          </section>
        </div>

        <!-- 결제 요약 + 제출 -->
        <aside class="h-fit rounded-card border border-line p-5 lg:sticky lg:top-24">
          <h2 class="mb-4 text-lg font-semibold text-ink">결제</h2>
          <!-- 결제 금액 요약(FE-17): 합계·배송비·최종 금액·종류/수량, 선택 0개·구매 불가 포함 안내 -->
          <CheckoutPaymentSummary :summary="vm.summary" />

          <!-- 오류 -->
          <div v-if="vm.errorMessage" class="mt-4">
            <p role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>
            <NuxtLink v-if="vm.showCartLink" to="/cart" class="mt-1 inline-block text-sm text-primary underline">
              장바구니로 이동
            </NuxtLink>
          </div>

          <Button type="submit" size="lg" class="mt-4 w-full" :disabled="vm.submitting || !vm.canSubmit">
            {{ vm.submitting ? '주문 처리 중…' : `${vm.formatPrice(vm.summary.finalAmount)} 결제하기` }}
          </Button>
        </aside>
      </form>
    </div>
  </div>
</template>
