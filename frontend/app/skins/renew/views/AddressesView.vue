<script setup lang="ts">
import type { Address } from '~/types/address'
import type { AddressesPageVm } from '~/skins/contracts/addresses'
import { formatPhone } from '~/lib/format/phone'
import MypageFrame from '../components/MypageFrame.vue'
import RenewAddressSearch from '../components/RenewAddressSearch.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 배송지 관리(FE-72 보완 1·2 · FE-79). 목록 = 기본 배송지 라벤더 띠 면 + 나머지 흰 카드(≥768 2열) · 추가·수정 = 폼 모달 · 삭제 = 확인 모달(공용 components/ui/dialog).
// 추가·수정·기본 지정·삭제와 문구·입력 제한은 페이지 vm 그대로다. 주소는 주문서와 같은 주소 검색(RenewAddressSearch — 폼 모달 위에 검색 모달이 겹친다)으로 채운다.
const props = defineProps<{ vm: AddressesPageVm }>()

// 기본 배송지는 서버가 1개로 유지한다 — 목록(0~1개)으로 두어 템플릿 핸들러에서 undefined 좁히기가 필요 없게 한다.
const defaultAddresses = computed<Address[]>(() => props.vm.data?.filter((address) => address.isDefault) ?? [])
const otherAddresses = computed<Address[]>(() => props.vm.data?.filter((address) => !address.isDefault) ?? [])

// 닫히는 동안(짧은 애니메이션) 제목·설명·입력칸이 바뀌어 보이지 않도록, 열려 있을 때의 값을 붙잡아 두고 닫힘이 끝난 뒤 초기화한다(FE-79).
const creating = ref(true)
// 폼 모달이 화면에 있는 동안(닫힘 애니메이션 포함) true — 폼 안 오류가 닫히는 사이 페이지 알림으로 번쩍이지 않게 한다.
const formShown = ref(false)
// 우편번호 검색 서비스 실패 후 "직접 입력"을 고른 상태(화면 상태만 · FE-78 A-2).
const manualAddressEntry = ref(false)
// 저장을 눌렀는데 주소가 비어 있을 때만 안내한다(처음 열 때는 보이지 않음).
const submitAttempted = ref(false)
watch(
  () => [props.vm.formOpen, props.vm.editingId] as const,
  ([open, editingId]) => {
    if (!open) return
    creating.value = editingId === null
    formShown.value = true
    manualAddressEntry.value = false
    submitAttempted.value = false
  },
  { immediate: true },
)
const removeDescription = ref('')
watch(
  () => props.vm.removeTargetId,
  (addressId) => {
    const target = addressId === null ? undefined : props.vm.data?.find((address) => address.id === addressId)
    if (target) removeDescription.value = `${target.recipientName} · ${target.addressRoad}`
  },
)

const addressMissing = computed(() => props.vm.form.zonecode.trim() === '' || props.vm.form.addressRoad.trim() === '')
const addressHintShown = computed(() => submitAttempted.value && !manualAddressEntry.value && addressMissing.value)

function onFormOpenChange(open: boolean): void {
  if (!open) props.vm.closeForm()
}
// 닫힘 애니메이션이 끝나 포커스가 돌아갈 때 = 모달이 사라진 뒤. 이때 폼을 비운다.
function onFormClosed(): void {
  formShown.value = false
  props.vm.resetForm()
}
// 주소 입력칸이 없어 브라우저 필수 검사가 닿지 않는다 — 주소가 비면 요청 없이 안내만 보인다(FE-79).
function onSubmit(): void {
  submitAttempted.value = true
  if (!manualAddressEntry.value && addressMissing.value) return
  void props.vm.handleSubmit()
}
function onRemoveOpenChange(open: boolean): void {
  if (!open) props.vm.cancelRemove()
}

const CARD = 'rounded-card bg-white p-5 shadow-e1 md:p-6'
const LABEL = 'mb-1.5 block text-small font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-control border border-line bg-white px-4 text-body text-ink transition duration-fast ease-soft placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
// 카드 액션: 기본으로 설정 = 보조 · 수정 = 3차 · 삭제 = 3차 위험색. <768 터치 영역 44.
const ACTION = 'btn btn-sm max-md:min-h-11'
</script>

<template>
  <MypageFrame title="배송지 관리">
    <template #actions>
      <button v-if="vm.data" type="button" class="btn btn-primary btn-md shrink-0" data-testid="address-add" @click="vm.openCreate">
        <span aria-hidden="true">+</span> 새 배송지 추가
      </button>
    </template>

    <!-- 로딩 -->
    <div v-if="vm.pending" class="grid gap-4 md:grid-cols-2" aria-hidden="true">
      <div v-for="index in 3" :key="index" :class="[CARD, 'space-y-3', index === 1 ? 'md:col-span-2' : '']">
        <div class="h-4 w-1/4 rounded-full bg-surface-muted"></div>
        <div class="h-3 w-1/3 rounded-full bg-surface-muted"></div>
        <div class="h-3 w-2/3 rounded-full bg-surface-muted"></div>
      </div>
    </div>

    <!-- 에러 / 없음 -->
    <CommonErrorState v-else-if="vm.error || !vm.data" message="배송지를 불러오지 못했습니다" @retry="vm.refresh" />

    <template v-else>
      <RenewNotice v-if="vm.successMessage" tone="success" class="mb-6">{{ vm.successMessage }}</RenewNotice>
      <!-- 폼 모달이 없을 때의 오류(기본 지정·삭제 실패 등). 모달이 있으면 모달 안에 보인다. -->
      <RenewNotice v-if="vm.errorMessage && !formShown" tone="danger" class="mb-6">{{ vm.errorMessage }}</RenewNotice>

      <div v-if="vm.data.length > 0" class="space-y-4">
        <!-- 기본 배송지: 라벤더 띠 면 한 장(≥768 왼쪽 정보 · 오른쪽 버튼) -->
        <section
          v-for="defaultAddress in defaultAddresses"
          :key="defaultAddress.id"
          class="rounded-(--panel-radius) bg-(--pastel-lavender-bg) p-5 md:flex md:items-center md:gap-8 md:p-6"
          aria-label="기본 배송지"
          data-testid="address-card"
        >
          <div class="min-w-0 flex-1">
            <p class="text-caption text-(--pastel-lavender-ink)">기본 배송지</p>
            <p class="mt-1 text-h3 text-ink">
              {{ defaultAddress.recipientName }}
              <span v-if="defaultAddress.addressLabel" class="ml-1 text-small text-sub">· {{ defaultAddress.addressLabel }}</span>
            </p>
            <p class="mt-2 text-small tabular-nums text-sub">{{ formatPhone(defaultAddress.recipientPhone) }}</p>
            <p class="mt-1 break-keep text-body text-ink">
              (<span class="tabular-nums">{{ defaultAddress.zonecode }}</span>) {{ defaultAddress.addressRoad }}
              <template v-if="defaultAddress.addressDetail"> {{ defaultAddress.addressDetail }}</template>
            </p>
          </div>
          <div class="mt-4 flex items-center justify-end gap-1 md:mt-0 md:shrink-0">
            <button type="button" :class="[ACTION, 'btn-tertiary']" data-testid="address-edit" @click="vm.startEdit(defaultAddress)">수정</button>
            <button type="button" :class="[ACTION, 'btn-tertiary text-destructive']" data-testid="address-remove" @click="vm.requestRemove(defaultAddress.id)">
              삭제
            </button>
          </div>
        </section>

        <!-- 나머지: 흰 카드 ≥768 2열 -->
        <ul v-if="otherAddresses.length > 0" class="grid gap-4 md:grid-cols-2" aria-label="배송지 목록">
          <li v-for="address in otherAddresses" :key="address.id" :class="[CARD, 'flex flex-col']" data-testid="address-card">
            <div class="min-w-0 flex-1">
              <p class="text-h3 text-ink">
                {{ address.recipientName }}
                <span v-if="address.addressLabel" class="ml-1 text-small text-sub">· {{ address.addressLabel }}</span>
              </p>
              <p class="mt-2 text-small tabular-nums text-sub">{{ formatPhone(address.recipientPhone) }}</p>
              <p class="mt-1 break-keep text-body text-ink">
                (<span class="tabular-nums">{{ address.zonecode }}</span>) {{ address.addressRoad }}
                <template v-if="address.addressDetail"> {{ address.addressDetail }}</template>
              </p>
            </div>
            <div class="mt-5 flex flex-wrap items-center gap-1 border-t border-line pt-4">
              <button type="button" :class="[ACTION, 'btn-secondary']" @click="vm.handleSetDefault(address.id)">기본으로 설정</button>
              <div class="ml-auto flex items-center gap-1">
                <button type="button" :class="[ACTION, 'btn-tertiary']" data-testid="address-edit" @click="vm.startEdit(address)">수정</button>
                <button type="button" :class="[ACTION, 'btn-tertiary text-destructive']" data-testid="address-remove" @click="vm.requestRemove(address.id)">
                  삭제
                </button>
              </div>
            </div>
          </li>
        </ul>
      </div>
      <div v-else :class="[CARD, 'flex flex-col items-center py-14 text-center']">
        <span class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
          <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 10.5a3 3 0 11-6 0 3 3 0 016 0z" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1115 0z" />
          </svg>
        </span>
        <p class="mt-5 text-h3 text-ink">등록된 배송지가 없습니다</p>
        <button type="button" class="btn btn-primary btn-md mt-5" @click="vm.openCreate">
          <span aria-hidden="true">+</span> 새 배송지 추가
        </button>
      </div>
    </template>

    <!-- 추가·수정 폼 모달 -->
    <Dialog :open="vm.formOpen" @update:open="onFormOpenChange">
      <DialogContent class="md:max-w-2xl" data-testid="address-form-dialog" @close-auto-focus="onFormClosed">
        <form class="flex min-h-0 flex-1 flex-col" @submit.prevent="onSubmit">
          <DialogHeader>
            <DialogTitle>{{ creating ? '새 배송지 추가' : '배송지 수정' }}</DialogTitle>
            <DialogDescription>받는 분과 주소를 입력해 주세요.</DialogDescription>
          </DialogHeader>

          <DialogBody class="grid content-start gap-4 md:grid-cols-2">
            <div>
              <label for="recipientName" :class="LABEL">받는 사람</label>
              <input id="recipientName" v-model="vm.form.recipientName" type="text" required :maxlength="vm.RECIPIENT_NAME_MAX" :class="INPUT" placeholder="받는 사람 이름" />
            </div>
            <div>
              <label for="recipientPhone" :class="LABEL">연락처</label>
              <input id="recipientPhone" v-model="vm.form.recipientPhone" type="tel" required :maxlength="vm.RECIPIENT_PHONE_MAX" :class="INPUT" placeholder="휴대폰 번호" />
            </div>

            <!-- 주소: 검색 버튼 또는 요약 카드(수정 모드는 기존 주소 요약 + "변경"). 검색 서비스 실패 후 "직접 입력"을 고른 때만 아래 세 칸을 펼친다(FE-78 A-2와 같음). -->
            <div class="md:col-span-2">
              <p :class="LABEL">주소</p>
              <RenewAddressSearch
                v-model:zonecode="vm.form.zonecode"
                v-model:address-road="vm.form.addressRoad"
                v-model:address-jibun="vm.form.addressJibun"
                v-model:manual-entry="manualAddressEntry"
                detail-input-id="addressDetail"
                nested
              />
              <!-- 알림 영역은 항상 두고 문구만 바꿔야 보조기기가 변화를 읽는다. -->
              <p aria-live="polite" class="text-small text-destructive" data-testid="address-form-address-hint">
                <span v-if="addressHintShown" class="mt-2 block">주소를 검색해 선택해 주세요.</span>
              </p>
            </div>
            <template v-if="manualAddressEntry">
              <div>
                <label for="zonecode" :class="LABEL">우편번호</label>
                <input id="zonecode" v-model="vm.form.zonecode" type="text" autocomplete="postal-code" required :maxlength="vm.ZONECODE_MAX" :class="INPUT" placeholder="우편번호" />
              </div>
              <div>
                <label for="addressRoad" :class="LABEL">도로명 주소</label>
                <input id="addressRoad" v-model="vm.form.addressRoad" type="text" required :maxlength="vm.ADDRESS_ROAD_MAX" :class="INPUT" placeholder="도로명 주소" />
              </div>
              <div>
                <label for="addressJibun" :class="LABEL">지번 주소 (선택)</label>
                <input id="addressJibun" v-model="vm.form.addressJibun" type="text" :maxlength="vm.ADDRESS_JIBUN_MAX" :class="INPUT" placeholder="지번 주소" />
              </div>
            </template>
            <div>
              <label for="addressDetail" :class="LABEL">상세 주소 (선택)</label>
              <input id="addressDetail" v-model="vm.form.addressDetail" type="text" :maxlength="vm.ADDRESS_DETAIL_MAX" :class="INPUT" placeholder="상세 주소(동·호수 등)" />
            </div>
            <div>
              <label for="addressLabel" :class="LABEL">배송지 이름 (선택)</label>
              <input id="addressLabel" v-model="vm.form.addressLabel" type="text" :maxlength="vm.ADDRESS_LABEL_MAX" :class="INPUT" placeholder="예: 집, 회사" />
            </div>

            <!-- isDefault는 추가 모드에서만(수정은 별도 기본 지정 경로). -->
            <label v-if="creating" class="flex min-h-11 w-fit cursor-pointer items-center gap-3 text-body text-ink md:col-span-2">
              <RenewCheckbox :checked="vm.form.isDefault" @change="(checked) => (vm.form.isDefault = checked)" />
              기본 배송지로 설정
            </label>

            <RenewNotice v-if="vm.errorMessage" tone="danger" class="md:col-span-2">{{ vm.errorMessage }}</RenewNotice>
          </DialogBody>

          <DialogFooter>
            <button type="button" class="btn btn-secondary btn-md" @click="vm.closeForm">취소</button>
            <button type="submit" class="btn btn-primary btn-md min-w-28" :disabled="vm.submitting" data-testid="address-form-submit">
              <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
              {{ vm.submitting ? '저장 중…' : '저장' }}
            </button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- 삭제 확인 모달: 설명 = 대상 · 결과 = 안내(FE-79) · 삭제가 끝날 때까지 열어 둔다(pending — 버튼 잠금·닫힘 무시). -->
    <DialogConfirm
      :open="vm.removeTargetId !== null"
      title="이 배송지를 삭제하시겠습니까?"
      :description="removeDescription"
      :confirm-label="vm.removing ? '삭제 중…' : '삭제하기'"
      tone="danger"
      :pending="vm.removing"
      @update:open="onRemoveOpenChange"
      @confirm="vm.confirmRemove"
    >
      <template #notice>삭제하면 되돌릴 수 없어요.</template>
    </DialogConfirm>
  </MypageFrame>
</template>
