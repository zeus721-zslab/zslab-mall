<script setup lang="ts">
import type { AddressesPageVm } from '~/skins/contracts/addresses'

defineProps<{ vm: AddressesPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <NuxtLink to="/mypage" class="mb-4 inline-block text-sm text-sub hover:underline">← 마이페이지</NuxtLink>
      <h1 class="mb-6 text-2xl font-bold tracking-tight text-ink">배송지 관리</h1>

      <!-- 로딩 -->
      <div v-if="vm.pending" class="space-y-3">
        <div v-for="n in 3" :key="n" class="h-24 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음 -->
      <CommonErrorState v-else-if="vm.error || !vm.data" message="배송지를 불러오지 못했습니다" @retry="vm.refresh" />

      <template v-else>
        <!-- 목록 -->
        <ul v-if="vm.data.length > 0" class="mb-8 space-y-3">
          <li
            v-for="address in vm.data"
            :key="address.id"
            class="rounded-card border border-line p-5"
            :class="{ 'border-gray-300': address.isDefault }"
          >
            <div class="flex items-start justify-between gap-4">
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <p class="text-base font-medium text-ink">{{ address.recipientName }}</p>
                  <span
                    v-if="address.isDefault"
                    class="rounded-badge bg-primary px-2 py-0.5 text-xs font-medium text-primary-foreground"
                  >
                    기본
                  </span>
                  <span v-if="address.addressLabel" class="text-xs text-sub">{{ address.addressLabel }}</span>
                </div>
                <p class="mt-1 text-sm text-sub">{{ address.recipientPhone }}</p>
                <p class="mt-1 text-sm text-ink">
                  ({{ address.zonecode }}) {{ address.addressRoad }}
                  <template v-if="address.addressDetail"> {{ address.addressDetail }}</template>
                </p>
              </div>
            </div>
            <div class="mt-3 flex items-center justify-end gap-2 border-t border-line pt-3">
              <Button
                v-if="!address.isDefault"
                variant="outline"
                size="sm"
                @click="vm.handleSetDefault(address.id)"
              >
                기본 지정
              </Button>
              <Button variant="outline" size="sm" @click="vm.startEdit(address)">수정</Button>
              <Button variant="ghost" size="sm" @click="vm.handleRemove(address.id)">삭제</Button>
            </div>
          </li>
        </ul>
        <CommonEmptyState v-else message="등록된 배송지가 없습니다" />

        <!-- 추가/수정 폼 -->
        <section class="rounded-card border border-line p-5">
          <div class="mb-4 flex items-center justify-between">
            <h2 class="text-base font-semibold text-ink">
              {{ vm.editingId === null ? '새 배송지 추가' : '배송지 수정' }}
            </h2>
            <Button v-if="vm.editingId !== null" variant="ghost" size="sm" @click="vm.resetForm">새로 추가</Button>
          </div>

          <form class="space-y-4" @submit.prevent="vm.handleSubmit">
            <div class="space-y-1.5">
              <label for="recipientName" class="block text-sm font-medium text-ink">받는 사람</label>
              <input
                id="recipientName"
                v-model="vm.form.recipientName"
                type="text"
                required
                :maxlength="vm.RECIPIENT_NAME_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="받는 사람 이름"
              />
            </div>

            <div class="space-y-1.5">
              <label for="recipientPhone" class="block text-sm font-medium text-ink">연락처</label>
              <input
                id="recipientPhone"
                v-model="vm.form.recipientPhone"
                type="tel"
                required
                :maxlength="vm.RECIPIENT_PHONE_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="휴대폰 번호"
              />
            </div>

            <div class="space-y-1.5">
              <label for="zonecode" class="block text-sm font-medium text-ink">우편번호</label>
              <input
                id="zonecode"
                v-model="vm.form.zonecode"
                type="text"
                required
                :maxlength="vm.ZONECODE_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="우편번호"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressRoad" class="block text-sm font-medium text-ink">도로명 주소</label>
              <input
                id="addressRoad"
                v-model="vm.form.addressRoad"
                type="text"
                required
                :maxlength="vm.ADDRESS_ROAD_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="도로명 주소"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressDetail" class="block text-sm font-medium text-ink">상세 주소 (선택)</label>
              <input
                id="addressDetail"
                v-model="vm.form.addressDetail"
                type="text"
                :maxlength="vm.ADDRESS_DETAIL_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="상세 주소(동·호수 등)"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressJibun" class="block text-sm font-medium text-ink">지번 주소 (선택)</label>
              <input
                id="addressJibun"
                v-model="vm.form.addressJibun"
                type="text"
                :maxlength="vm.ADDRESS_JIBUN_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="지번 주소"
              />
            </div>

            <div class="space-y-1.5">
              <label for="addressLabel" class="block text-sm font-medium text-ink">배송지 이름 (선택)</label>
              <input
                id="addressLabel"
                v-model="vm.form.addressLabel"
                type="text"
                :maxlength="vm.ADDRESS_LABEL_MAX"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="예: 집, 회사"
              />
            </div>

            <!-- isDefault는 생성 시에만 노출(수정은 별도 기본 지정 경로). -->
            <label v-if="vm.editingId === null" class="flex items-center gap-2 text-sm text-ink">
              <input v-model="vm.form.isDefault" type="checkbox" class="h-4 w-4 rounded border-line" />
              기본 배송지로 설정
            </label>

            <p v-if="vm.errorMessage" role="alert" class="text-sm text-soldout">{{ vm.errorMessage }}</p>

            <Button type="submit" size="lg" class="w-full" :disabled="vm.submitting">
              {{ vm.submitting ? '저장 중…' : vm.editingId === null ? '배송지 추가' : '수정 저장' }}
            </Button>
          </form>
        </section>

        <p v-if="vm.successMessage" role="status" class="mt-4 text-sm text-primary">{{ vm.successMessage }}</p>
      </template>
    </div>
  </div>
</template>
