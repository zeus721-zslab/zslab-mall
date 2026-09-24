<script setup lang="ts">
import type { AddressesPageVm } from '~/skins/contracts/addresses'
import MypageFrame from '../components/MypageFrame.vue'
import RenewCheckbox from '../components/RenewCheckbox.vue'
import RenewNotice from '../components/RenewNotice.vue'

// renew 배송지 관리(FE-72 보완 1·2). 목록 = 1열 행(≥768 가로형) · 추가·수정 = 폼 모달 · 삭제 = 확인 모달(공용 components/ui/dialog).
// 추가·수정·기본 지정·삭제와 문구·입력 제한은 페이지 vm 그대로다(classic은 같은 페이지의 기존 필드·handleRemove를 계속 쓴다).
const props = defineProps<{ vm: AddressesPageVm }>()

// 닫히는 동안(짧은 애니메이션) closeForm·confirmRemove가 상태를 먼저 비워도 제목·설명이 바뀌어 보이지 않도록, 열려 있을 때의 값을 붙잡아 둔다.
const creating = ref(true)
watch(
  () => [props.vm.formOpen, props.vm.editingId] as const,
  ([open, editingId]) => {
    if (open) creating.value = editingId === null
  },
  { immediate: true },
)
const removeDescription = ref('')
watch(
  () => props.vm.removeTargetId,
  (addressId) => {
    const target = addressId === null ? undefined : props.vm.data?.find((address) => address.id === addressId)
    if (target) removeDescription.value = `${target.recipientName} · ${target.addressRoad} — 삭제하면 되돌릴 수 없습니다.`
  },
)

function onFormOpenChange(open: boolean): void {
  if (!open) props.vm.closeForm()
}
function onRemoveOpenChange(open: boolean): void {
  if (!open) props.vm.cancelRemove()
}

const CARD = 'rounded-[28px] bg-white p-6 md:p-7'
const LABEL = 'mb-1.5 block text-sm font-bold text-ink'
const INPUT =
  'h-12 w-full rounded-[14px] border border-line bg-white px-4 text-sm text-ink transition duration-200 placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
const PILL =
  'flex min-h-11 shrink-0 items-center justify-center gap-1.5 rounded-full px-5 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40'
const PRIMARY_PILL = `${PILL} bg-primary text-primary-foreground hover:bg-primary-hover disabled:hover:bg-primary`
const SMALL_BUTTON =
  'flex min-h-10 items-center rounded-full px-4 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary'
</script>

<template>
  <MypageFrame title="배송지 관리">
    <template #actions>
      <button v-if="vm.data" type="button" :class="PRIMARY_PILL" data-testid="address-add" @click="vm.openCreate">
        <span aria-hidden="true">+</span> 새 배송지 추가
      </button>
    </template>

    <!-- 로딩 -->
    <div v-if="vm.pending" class="space-y-4" aria-hidden="true">
      <div v-for="index in 3" :key="index" :class="[CARD, 'space-y-3']">
        <div class="h-4 w-1/4 rounded-full bg-surface-muted"></div>
        <div class="h-3 w-1/3 rounded-full bg-surface-muted"></div>
        <div class="h-3 w-2/3 rounded-full bg-surface-muted"></div>
      </div>
    </div>

    <!-- 에러 / 없음 -->
    <CommonErrorState v-else-if="vm.error || !vm.data" message="배송지를 불러오지 못했습니다" @retry="vm.refresh" />

    <template v-else>
      <RenewNotice v-if="vm.successMessage" tone="success" class="mb-6">{{ vm.successMessage }}</RenewNotice>
      <!-- 폼 모달이 닫혀 있을 때의 오류(기본 지정·삭제 실패 등). 열려 있으면 모달 안에 보인다. -->
      <RenewNotice v-if="vm.errorMessage && !vm.formOpen" tone="danger" class="mb-6">{{ vm.errorMessage }}</RenewNotice>

      <!-- 목록: 모든 폭 1열. ≥768 가로형 행(왼쪽 정보 · 오른쪽 버튼), <768 버튼은 아래 줄. -->
      <ul v-if="vm.data.length > 0" class="space-y-4" aria-label="배송지 목록">
        <li v-for="address in vm.data" :key="address.id" :class="[CARD, 'md:flex md:items-center md:gap-8']" data-testid="address-card">
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2">
              <p class="text-base font-bold text-ink">{{ address.recipientName }}</p>
              <span v-if="address.isDefault" class="rounded-full bg-primary px-2.5 py-0.5 text-xs font-bold text-primary-foreground">기본</span>
              <span v-if="address.addressLabel" class="rounded-full bg-surface-muted px-2.5 py-0.5 text-xs font-bold text-sub">{{ address.addressLabel }}</span>
            </div>
            <p class="mt-2 font-mono text-sm text-sub">{{ address.recipientPhone }}</p>
            <p class="mt-1 break-keep text-sm text-ink">
              ({{ address.zonecode }}) {{ address.addressRoad }}
              <template v-if="address.addressDetail"> {{ address.addressDetail }}</template>
            </p>
          </div>
          <div class="mt-5 flex flex-wrap items-center gap-2 border-t border-line pt-4 md:mt-0 md:shrink-0 md:border-t-0 md:pt-0">
            <button
              v-if="!address.isDefault"
              type="button"
              :class="[SMALL_BUTTON, 'text-primary hover:bg-surface-muted max-md:-ml-4']"
              @click="vm.handleSetDefault(address.id)"
            >
              기본으로 설정
            </button>
            <div class="ml-auto flex items-center gap-2">
              <button type="button" :class="[SMALL_BUTTON, 'border border-line bg-white text-ink hover:border-ink']" data-testid="address-edit" @click="vm.startEdit(address)">
                수정
              </button>
              <button type="button" :class="[SMALL_BUTTON, 'text-sub hover:bg-surface-muted hover:text-ink']" data-testid="address-remove" @click="vm.requestRemove(address.id)">
                삭제
              </button>
            </div>
          </div>
        </li>
      </ul>
      <div v-else :class="[CARD, 'flex flex-col items-center py-14 text-center']">
        <span class="flex h-16 w-16 items-center justify-center rounded-full bg-surface-muted text-primary" aria-hidden="true">
          <svg class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.6">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 10.5a3 3 0 11-6 0 3 3 0 016 0z" />
            <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 10.5c0 7.142-7.5 11.25-7.5 11.25S4.5 17.642 4.5 10.5a7.5 7.5 0 1115 0z" />
          </svg>
        </span>
        <p class="mt-5 text-base font-bold text-ink">등록된 배송지가 없습니다</p>
        <button type="button" :class="[PRIMARY_PILL, 'mt-5']" @click="vm.openCreate">
          <span aria-hidden="true">+</span> 새 배송지 추가
        </button>
      </div>
    </template>

    <!-- 추가·수정 폼 모달 -->
    <Dialog :open="vm.formOpen" @update:open="onFormOpenChange">
      <DialogContent class="md:max-w-2xl" data-testid="address-form-dialog">
        <form class="flex min-h-0 flex-1 flex-col" @submit.prevent="vm.handleSubmit">
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
            <div>
              <label for="zonecode" :class="LABEL">우편번호</label>
              <input id="zonecode" v-model="vm.form.zonecode" type="text" required :maxlength="vm.ZONECODE_MAX" :class="INPUT" placeholder="우편번호" />
            </div>
            <div>
              <label for="addressRoad" :class="LABEL">도로명 주소</label>
              <input id="addressRoad" v-model="vm.form.addressRoad" type="text" required :maxlength="vm.ADDRESS_ROAD_MAX" :class="INPUT" placeholder="도로명 주소" />
            </div>
            <div>
              <label for="addressDetail" :class="LABEL">상세 주소 (선택)</label>
              <input id="addressDetail" v-model="vm.form.addressDetail" type="text" :maxlength="vm.ADDRESS_DETAIL_MAX" :class="INPUT" placeholder="상세 주소(동·호수 등)" />
            </div>
            <div>
              <label for="addressJibun" :class="LABEL">지번 주소 (선택)</label>
              <input id="addressJibun" v-model="vm.form.addressJibun" type="text" :maxlength="vm.ADDRESS_JIBUN_MAX" :class="INPUT" placeholder="지번 주소" />
            </div>
            <div>
              <label for="addressLabel" :class="LABEL">배송지 이름 (선택)</label>
              <input id="addressLabel" v-model="vm.form.addressLabel" type="text" :maxlength="vm.ADDRESS_LABEL_MAX" :class="INPUT" placeholder="예: 집, 회사" />
            </div>

            <!-- isDefault는 추가 모드에서만(수정은 별도 기본 지정 경로). -->
            <label v-if="creating" class="flex w-fit cursor-pointer items-center gap-3 text-sm text-ink md:col-span-2">
              <RenewCheckbox :checked="vm.form.isDefault" @change="(checked) => (vm.form.isDefault = checked)" />
              기본 배송지로 설정
            </label>

            <RenewNotice v-if="vm.errorMessage" tone="danger" class="md:col-span-2">{{ vm.errorMessage }}</RenewNotice>
          </DialogBody>

          <DialogFooter>
            <button type="button" :class="[PILL, 'border border-line bg-white text-ink hover:border-ink']" @click="vm.closeForm">취소</button>
            <button type="submit" :class="[PRIMARY_PILL, 'min-w-28']" :disabled="vm.submitting" data-testid="address-form-submit">
              <span v-if="vm.submitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
              {{ vm.submitting ? '저장 중…' : '저장' }}
            </button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>

    <!-- 삭제 확인 모달 -->
    <DialogConfirm
      :open="vm.removeTargetId !== null"
      title="이 배송지를 삭제하시겠습니까?"
      :description="removeDescription"
      confirm-label="삭제"
      destructive
      @update:open="onRemoveOpenChange"
      @confirm="vm.confirmRemove"
    />
  </MypageFrame>
</template>
