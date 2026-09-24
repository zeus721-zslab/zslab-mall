<script setup lang="ts">
import type { CheckoutPageVm } from '~/skins/contracts/checkout'
import { formatPhone } from '~/lib/format/phone'
import FadeAmount from '../components/FadeAmount.vue'
import MobileActionBar from '../components/MobileActionBar.vue'
import RenewAddressSearch from '../components/RenewAddressSearch.vue'
import RenewBadge from '../components/RenewBadge.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 주문서(FE-71 · FE-78). 번호 섹션 ① 배송지 ② 결제 수단 ③ 주문 상품(흰 카드) + 오른쪽 결제 금액 카드(장바구니와 같은 배치·sticky).
// 저장 배송지를 고른 상태는 입력칸 대신 요약 카드로 보여 주고 "이 주소 수정"으로 입력칸을 펼친다(재구매 확인 위주·로직 불변).
// 입력칸은 접혀도 DOM에 남아 v-model이 classic과 같게 동작한다(접힘 동안 inert로 초점·보조기기 제외). 주소 필수 여부는 페이지 canSubmit이 판정한다.
const props = defineProps<{ vm: CheckoutPageVm }>()

const CONTAINER = 'mx-auto max-w-[1440px] px-5 md:px-10 lg:px-16'
const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const STEP_NUMBER = 'flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-ink text-small font-semibold tabular-nums text-white'
const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
// 저장 배송지 선택 칩: 실제 radio(sr-only)를 감싸므로 선택 표시는 data-state, 초점 표시는 has-focus-visible로 준다.
const ADDRESS_CHIP = 'chip cursor-pointer has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary has-[:focus-visible]:ring-offset-2'
const NEW_ADDRESS_KEY = 'new'
// 알림 action 슬롯의 장바구니 링크: 띠 면 위 보조 버튼(흰 바탕). 배치는 RenewNotice가 맡는다(FE-79).
const CART_LINK = 'btn btn-sm bg-white text-primary max-md:min-h-11'

// "이 주소 수정"으로 펼친 상태(화면 상태만). 다른 배송지를 고르면 다시 요약으로 접는다.
const editing = ref(false)
const expanded = computed(() => !props.vm.hasAddresses || props.vm.selectedKey === NEW_ADDRESS_KEY || editing.value)

function onPickAddress(): void {
  editing.value = false
  props.vm.onSelectAddress()
}

// 우편번호·도로명·지번은 주소 검색으로 채운다. 검색 서비스 로드 실패 후 "직접 입력"을 고르면 RenewAddressSearch가 켜 입력칸을 펼친다(FE-78 A-2).
const manualAddressEntry = ref(false)
// 주소가 비면 결제하기가 비활성이라 제출 시 필수 안내가 뜨지 않는다 — 받는 사람·연락처를 채우고 주소만 남았을 때 이유를 알린다(FE-78 A-2).
// 처음 진입(두 칸이 빈 상태)에는 보이지 않고, 직접 입력 중에는 입력칸 자체가 보이므로 띄우지 않는다.
const addressHintShown = computed(
  () =>
    !manualAddressEntry.value &&
    props.vm.recipientName.trim() !== '' &&
    props.vm.recipientPhone.trim() !== '' &&
    (props.vm.zonecode.trim() === '' || props.vm.addressRoad.trim() === ''),
)

const submitLabel = computed(() => (props.vm.submitting ? '주문 처리 중…' : '결제하기'))
// 결제 금액 카드의 결제하기: 화면에 없을 때만 하단 고정 바가 나타난다(FE-71 도킹).
const submitButton = ref<HTMLButtonElement | null>(null)
</script>

<template>
  <div class="pb-8 pt-6 md:pt-10">
    <div :class="CONTAINER">
      <h1 class="mb-8 text-h1 text-ink">주문/결제</h1>

      <form class="lg:grid lg:grid-cols-[1fr_400px] lg:items-start lg:gap-8" @submit.prevent="vm.handleSubmit">
        <div class="space-y-6">
          <!-- ① 배송지 -->
          <section :class="CARD" aria-labelledby="checkout-address-title">
            <h2 id="checkout-address-title" class="flex items-center gap-3 text-h3 text-ink">
              <span :class="STEP_NUMBER" aria-hidden="true">1</span>배송지
            </h2>

            <RenewNotice v-if="vm.addressLoadFailed" tone="warning" class="mt-4">
              저장된 배송지를 불러오지 못했습니다. 배송지를 직접 입력해 주세요.
            </RenewNotice>

            <!-- 저장 배송지 칩 + 새 주소 입력(실제 radio) -->
            <div v-if="vm.hasAddresses" role="radiogroup" aria-label="저장된 배송지" class="mt-5 flex flex-wrap gap-2">
              <label
                v-for="address in vm.addressList"
                :key="address.id"
                :class="ADDRESS_CHIP"
                :data-state="vm.selectedKey === String(address.id) ? 'on' : undefined"
              >
                <input v-model="vm.selectedKey" type="radio" name="savedAddress" :value="String(address.id)" class="sr-only" @change="onPickAddress" />
                {{ address.addressLabel || address.recipientName }}
                <RenewBadge v-if="address.isDefault" tone="info">기본</RenewBadge>
              </label>
              <label :class="ADDRESS_CHIP" :data-state="vm.selectedKey === NEW_ADDRESS_KEY ? 'on' : undefined">
                <input v-model="vm.selectedKey" type="radio" name="savedAddress" :value="NEW_ADDRESS_KEY" class="sr-only" @change="onPickAddress" />
                새 주소 입력
              </label>
            </div>

            <!-- 저장 배송지 요약(접힘 상태) -->
            <div v-if="!expanded" class="mt-5 flex flex-col gap-4 rounded-card bg-surface-muted p-5 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0 space-y-1 text-body text-ink">
                <p class="font-bold">{{ vm.recipientName }} <span class="ml-1 font-normal tabular-nums text-sub">{{ formatPhone(vm.recipientPhone) }}</span></p>
                <p class="break-keep">({{ vm.zonecode }}) {{ vm.addressRoad }} {{ vm.addressDetail }}</p>
              </div>
              <button type="button" class="btn btn-md shrink-0 self-start bg-white text-primary" @click="editing = true">이 주소 수정</button>
            </div>

            <!-- 입력칸: 펼침 높이 전환(움직임 줄이기면 즉시) -->
            <div
              :class="[
                'grid transition-[grid-template-rows] duration-fast ease-soft motion-reduce:transition-none',
                expanded ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]',
              ]"
              :inert="!expanded || undefined"
            >
              <div class="min-h-0 overflow-hidden">
                <div class="grid gap-4 p-1 pt-5 sm:grid-cols-2">
                  <div>
                    <label for="recipientName" :class="LABEL">받는 사람 <span class="text-primary">*</span></label>
                    <input id="recipientName" v-model="vm.recipientName" type="text" autocomplete="name" required :maxlength="vm.RECIPIENT_NAME_MAX" :class="INPUT" placeholder="받는 사람 이름" />
                  </div>
                  <div>
                    <label for="recipientPhone" :class="LABEL">연락처 <span class="text-primary">*</span></label>
                    <input id="recipientPhone" v-model="vm.recipientPhone" type="tel" autocomplete="tel" required :maxlength="vm.RECIPIENT_PHONE_MAX" :class="INPUT" placeholder="010-0000-0000" />
                  </div>
                  <!-- 주소: 검색 버튼 또는 요약 카드. 검색 서비스 실패 후 "직접 입력"을 고른 때만 아래 세 칸을 펼친다(FE-78 A-2). -->
                  <div class="sm:col-span-2">
                    <p :class="LABEL">주소 <span class="text-primary">*</span></p>
                    <RenewAddressSearch
                      v-model:zonecode="vm.zonecode"
                      v-model:address-road="vm.addressRoad"
                      v-model:address-jibun="vm.addressJibun"
                      v-model:manual-entry="manualAddressEntry"
                      detail-input-id="addressDetail"
                    />
                    <!-- 알림 영역은 항상 두고 문구만 바꿔야 보조기기가 변화를 읽는다. -->
                    <p aria-live="polite" class="text-small text-destructive" data-testid="checkout-address-hint">
                      <span v-if="addressHintShown" class="mt-2 block">주소를 검색해 선택해 주세요.</span>
                    </p>
                  </div>
                  <template v-if="manualAddressEntry">
                    <div>
                      <label for="zonecode" :class="LABEL">우편번호 <span class="text-primary">*</span></label>
                      <input id="zonecode" v-model="vm.zonecode" type="text" autocomplete="postal-code" required :maxlength="vm.ZONECODE_MAX" :class="INPUT" placeholder="우편번호" />
                    </div>
                    <div class="sm:col-span-2">
                      <label for="addressRoad" :class="LABEL">도로명 주소 <span class="text-primary">*</span></label>
                      <input id="addressRoad" v-model="vm.addressRoad" type="text" autocomplete="address-line1" required :maxlength="vm.ADDRESS_ROAD_MAX" :class="INPUT" placeholder="도로명 주소" />
                    </div>
                    <div class="sm:col-span-2">
                      <label for="addressJibun" :class="LABEL">지번 주소</label>
                      <input id="addressJibun" v-model="vm.addressJibun" type="text" :maxlength="vm.ADDRESS_JIBUN_MAX" :class="INPUT" placeholder="지번 주소 (선택)" />
                    </div>
                  </template>
                  <div class="sm:col-span-2">
                    <label for="addressDetail" :class="LABEL">상세 주소</label>
                    <input id="addressDetail" v-model="vm.addressDetail" type="text" autocomplete="address-line2" :maxlength="vm.ADDRESS_DETAIL_MAX" :class="INPUT" placeholder="상세 주소 (선택)" />
                  </div>
                </div>
              </div>
            </div>

            <div class="mt-4 px-1">
              <label for="deliveryMemo" :class="LABEL">배송 메모</label>
              <input id="deliveryMemo" v-model="vm.deliveryMemo" type="text" :class="INPUT" placeholder="배송 시 요청사항 (선택)" />
            </div>

            <!-- 배송지 저장(FE-16): 새 주소이거나 불러온 주소를 수정했을 때만 -->
            <label v-if="vm.showSaveCheckbox" class="mt-4 flex min-h-11 w-fit cursor-pointer items-center gap-3 px-1 text-body text-ink">
              <RenewCheckbox :checked="vm.saveAddress" @change="(checked) => (vm.saveAddress = checked)" />
              이 배송지를 주소록에 저장
            </label>
          </section>

          <!-- ② 결제 수단: 카드형 라디오 ≥768 4열 · <768 2열 -->
          <section :class="CARD" aria-labelledby="checkout-method-title">
            <h2 id="checkout-method-title" class="flex items-center gap-3 text-h3 text-ink">
              <span :class="STEP_NUMBER" aria-hidden="true">2</span>결제 수단
            </h2>
            <div role="radiogroup" aria-labelledby="checkout-method-title" class="mt-5 grid grid-cols-2 gap-3 md:grid-cols-4">
              <label
                v-for="option in vm.PAYMENT_METHODS"
                :key="option.value"
                :class="[
                  'flex min-h-14 cursor-pointer items-center justify-center rounded-card border-2 px-3 text-center text-body font-bold transition duration-fast ease-soft has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary has-[:focus-visible]:ring-offset-2',
                  vm.method === option.value ? 'border-primary bg-surface-muted text-ink' : 'border-line bg-white text-sub hover:border-ink hover:text-ink',
                ]"
              >
                <input v-model="vm.method" type="radio" name="method" :value="option.value" class="sr-only" />
                {{ option.label }}
              </label>
            </div>
          </section>

          <!-- ③ 주문 상품 -->
          <section :class="CARD" aria-labelledby="checkout-items-title">
            <h2 id="checkout-items-title" class="flex items-center gap-3 text-h3 text-ink">
              <span :class="STEP_NUMBER" aria-hidden="true">3</span>주문 상품
            </h2>
            <div class="mt-5">
              <CommonErrorState v-if="vm.cartError" message="주문 상품을 불러오지 못했습니다" @retry="vm.refreshCart" />
              <ul v-else-if="vm.summary.items.length > 0" class="divide-y divide-line">
                <li v-for="item in vm.summary.items" :key="item.variantPublicId" class="flex items-center gap-4 py-4 first:pt-0 last:pb-0">
                  <div v-if="item.thumbnailUrl" class="h-16 w-16 shrink-0 overflow-hidden rounded-[14px] bg-(--image-placeholder)">
                    <img :src="item.thumbnailUrl" :alt="item.productName ?? '상품 이미지'" class="h-full w-full object-cover" />
                  </div>
                  <div class="min-w-0 flex-1">
                    <p class="truncate text-body font-bold text-ink">{{ item.productName ?? '상품 정보 없음' }}</p>
                    <p v-if="item.optionLabel" data-testid="item-option-label" class="truncate text-caption font-normal text-sub">{{ item.optionLabel }}</p>
                    <p class="mt-0.5 text-caption font-normal text-sub">수량 <span class="tabular-nums">{{ item.quantity }}</span></p>
                    <p v-if="!item.purchasable" class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1">
                      <RenewBadge tone="danger">구매 불가</RenewBadge>
                      <span class="text-caption font-normal text-sub">품절 또는 판매 중지</span>
                    </p>
                  </div>
                  <p class="shrink-0 text-ink">
                    <span class="text-body font-semibold tabular-nums">{{ (item.displayPrice * item.quantity).toLocaleString('ko-KR') }}</span><span class="ml-0.5 text-small">원</span>
                  </p>
                </li>
              </ul>
            </div>
          </section>
        </div>

        <!-- 결제 금액: ≥1024 헤더 아래 24px에 고정 -->
        <aside :class="[CARD, 'mt-6 lg:sticky lg:top-[calc(var(--header-height)_+_24px)] lg:mt-0']" aria-label="결제 금액">
          <h2 class="text-h3 text-ink">결제 금액</h2>

          <RenewNotice v-if="vm.summary.items.length === 0" tone="info" class="mt-4">
            <p>선택된 상품이 없습니다</p>
            <template #action>
              <NuxtLink to="/cart" :class="CART_LINK">장바구니로 이동</NuxtLink>
            </template>
          </RenewNotice>
          <template v-else>
            <RenewNotice v-if="vm.summary.hasUnpurchasableSelected" tone="danger" class="mt-4">
              <p>구매할 수 없는 상품이 포함되어 있습니다. 장바구니에서 삭제해 주세요.</p>
              <template #action>
                <NuxtLink to="/cart" :class="CART_LINK">장바구니로 이동</NuxtLink>
              </template>
            </RenewNotice>
            <dl class="mt-5 space-y-3 text-body">
              <div class="flex items-baseline justify-between">
                <dt class="text-sub">상품 금액</dt>
                <dd><FadeAmount :value="vm.summary.productTotal" /></dd>
              </div>
              <div class="flex items-baseline justify-between">
                <dt class="text-sub">배송비</dt>
                <dd class="font-semibold tabular-nums text-ink">{{ vm.summary.shippingFee === 0 ? '무료' : vm.formatPrice(vm.summary.shippingFee) }}</dd>
              </div>
              <div class="flex items-baseline justify-between border-t border-line pt-4">
                <dt class="font-semibold text-ink">총 결제 금액</dt>
                <dd class="text-h2"><FadeAmount :value="vm.summary.finalAmount" /></dd>
              </div>
            </dl>
            <p class="mt-2 text-right text-caption font-normal text-sub">
              총 <span class="tabular-nums">{{ vm.summary.kindCount }}</span>종 · <span class="tabular-nums">{{ vm.summary.totalQuantity }}</span>개
            </p>
          </template>

          <!-- 오류·장바구니 링크: 버튼 바로 위 -->
          <RenewNotice v-if="vm.errorMessage" tone="danger" class="mt-5">
            <p>{{ vm.errorMessage }}</p>
            <template v-if="vm.showCartLink" #action>
              <NuxtLink to="/cart" :class="CART_LINK">장바구니로 이동</NuxtLink>
            </template>
          </RenewNotice>

          <button ref="submitButton" type="submit" class="btn btn-primary btn-lg mt-5 w-full" :disabled="vm.submitting || !vm.canSubmit">
            <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
            {{ submitLabel }}
          </button>
        </aside>

        <!-- <1024 하단 고정 바: 결제하기가 화면에 없을 때만·같은 form 제출·같은 비활성 규칙 -->
        <MobileActionBar
          :anchor="submitButton"
          label="총 결제 금액"
          :amount="vm.summary.finalAmount"
          pending-text=""
          :button-label="submitLabel"
          :disabled="vm.submitting || !vm.canSubmit"
          submit
        />
      </form>
    </div>
  </div>
</template>
