<script setup lang="ts">
import type { ClaimDetailPageVm } from '~/skins/contracts/claim-detail'

defineProps<{ vm: ClaimDetailPageVm }>()
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <!-- 로딩 -->
      <div v-if="vm.pending" class="space-y-4">
        <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100"></div>
        <div class="h-40 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음(404 포함) -->
      <CommonErrorState v-else-if="vm.error || !vm.data" :message="vm.errorMessage" @retry="vm.refresh" />

      <!-- 상세 -->
      <template v-else>
        <!-- 헤더: 클레임 유형 + 상태 -->
        <div class="mb-6 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-sm text-sub">클레임 유형</p>
            <h1 class="mt-1 text-lg font-medium text-ink">{{ vm.claimTypeLabel(vm.data.claimType) }}</h1>
          </div>
          <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-sm font-medium text-ink">
            {{ vm.claimStatusLabel(vm.data.status) }}
          </span>
        </div>

        <!-- 요청 취소(Track 101-A): 접수 상태에서만 노출. 승인 이후에는 운영자 판단이 필요해 버튼을 감춘다. -->
        <div v-if="vm.cancellable" class="mb-6" data-testid="claim-cancel-block">
          <template v-if="!vm.cancelConfirmOpen">
            <Button variant="outline" size="sm" data-testid="claim-cancel-open" @click="vm.cancelConfirmOpen = true">
              요청 취소
            </Button>
          </template>
          <div v-else class="rounded-card border border-line bg-gray-50 p-4" data-testid="claim-cancel-panel">
            <p class="text-sm font-medium text-ink">{{ vm.claimTypeLabel(vm.data.claimType) }} 요청을 취소할까요?</p>
            <p class="mt-1 text-sm text-soldout" style="white-space: pre-line" data-testid="claim-cancel-warning">{{ vm.CLAIM_CANCEL_WARNING }}</p>
            <div class="mt-3 flex gap-2">
              <Button variant="destructive" size="sm" :disabled="vm.cancelSubmitting" data-testid="claim-cancel-submit" @click="vm.submitCancel">
                {{ vm.cancelSubmitting ? '취소 중…' : '요청 취소' }}
              </Button>
              <Button variant="outline" size="sm" :disabled="vm.cancelSubmitting" data-testid="claim-cancel-dismiss" @click="vm.cancelConfirmOpen = false">
                닫기
              </Button>
            </div>
          </div>
          <p v-if="vm.cancelError" class="mt-2 text-sm text-soldout" data-testid="claim-cancel-error">{{ vm.cancelError }}</p>
        </div>
        <p v-else-if="vm.cancelError" class="mb-6 text-sm text-soldout" data-testid="claim-cancel-error">{{ vm.cancelError }}</p>

        <!-- 진행 타임라인 -->
        <section class="rounded-card border border-line p-5">
          <h2 class="mb-5 text-base font-semibold text-ink">진행 상태</h2>
          <ol class="flex items-start" data-testid="claim-timeline">
            <template v-for="(step, index) in vm.timeline" :key="step.label">
              <li class="flex min-w-[3rem] flex-col items-center gap-1.5 text-center" data-testid="claim-timeline-step">
                <span
                  class="flex h-8 w-8 items-center justify-center rounded-full text-xs font-semibold"
                  :class="vm.stepCircleClass(step.state)"
                >
                  {{ index + 1 }}
                </span>
                <span
                  class="text-xs"
                  :class="step.state === 'upcoming' ? 'text-sub' : 'font-medium text-ink'"
                >
                  {{ step.label }}
                </span>
                <span v-if="step.at" class="text-[11px] text-sub">{{ vm.formatDateTime(step.at) }}</span>
              </li>
              <!-- 연결선: 이전 스텝을 통과(done)했으면 강조. 원 중심 높이에 맞춰 정렬. -->
              <span
                v-if="index < vm.timeline.length - 1"
                class="mx-1 mt-4 h-0.5 flex-1"
                :class="step.state === 'done' ? 'bg-primary' : 'bg-line'"
              ></span>
            </template>
          </ol>
          <p class="mt-4 text-sm text-ink" data-testid="claim-stage-guide">{{ vm.stageGuide }}</p>
        </section>

        <!-- 클레임 정보 -->
        <section class="mt-6 rounded-card border border-line p-5">
          <h2 class="mb-3 text-base font-semibold text-ink">클레임 정보</h2>
          <dl class="space-y-3 text-sm">
            <div class="flex justify-between gap-4">
              <dt class="text-sub">사유</dt>
              <dd class="text-right text-ink">{{ vm.CLAIM_REASON_LABELS[vm.data.reasonCode] }}</dd>
            </div>
            <div v-if="vm.data.reasonDetail" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">상세 사유</dt>
              <dd class="whitespace-pre-line text-right text-ink">{{ vm.data.reasonDetail }}</dd>
            </div>
            <!-- 교환 옵션(FE-30·D-177): EXCHANGE만·라벨이 둘 다 없으면 행 숨김 -->
            <div v-if="vm.data.claimType === 'EXCHANGE' && (vm.data.originalOptionLabel || vm.data.exchangeOptionLabel)" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">교환 옵션</dt>
              <dd class="text-right text-ink" data-testid="claim-exchange-option">
                {{ vm.data.originalOptionLabel ?? '—' }} → {{ vm.data.exchangeOptionLabel ?? '—' }}
              </dd>
            </div>
            <div class="flex justify-between gap-4">
              <dt class="text-sub">요청 일시</dt>
              <dd class="text-right text-ink">{{ vm.formatDateTime(vm.data.requestedAt) }}</dd>
            </div>
            <div v-if="vm.data.processedAt" class="flex justify-between gap-4">
              <dt class="text-sub">처리 일시</dt>
              <dd class="text-right text-ink">{{ vm.formatDateTime(vm.data.processedAt) }}</dd>
            </div>
            <!-- 반품 회수·검수·재발송(FE-29·Track 81-A): 값이 있을 때만 행 노출 -->
            <div v-if="vm.data.returnShipment" class="flex justify-between gap-4">
              <dt class="text-sub">회수 송장</dt>
              <dd class="text-right text-ink" data-testid="claim-return-shipment">
                {{ vm.deliveryCarrierLabel(vm.data.returnShipment.carrier) }} {{ vm.data.returnShipment.trackingNo }}
              </dd>
            </div>
            <div v-if="vm.data.pickedUpAt" class="flex justify-between gap-4">
              <dt class="text-sub">회수 확인</dt>
              <dd class="text-right text-ink" data-testid="claim-picked-up-at">{{ vm.formatDateTime(vm.data.pickedUpAt) }}</dd>
            </div>
            <div v-if="vm.data.inspectionResult" class="flex justify-between gap-4">
              <dt class="text-sub">검수 결과</dt>
              <dd class="text-right text-ink" data-testid="claim-inspection-result">{{ vm.CLAIM_INSPECTION_RESULT_LABELS[vm.data.inspectionResult] }}</dd>
            </div>
            <div v-if="vm.data.reshipment" class="flex justify-between gap-4">
              <dt class="text-sub" data-testid="claim-reshipment-label">{{ vm.data.claimType === 'EXCHANGE' ? '교환품 배송 송장' : '재발송 송장' }}</dt>
              <dd class="text-right text-ink" data-testid="claim-reshipment">
                {{ vm.deliveryCarrierLabel(vm.data.reshipment.carrier) }} {{ vm.data.reshipment.trackingNo }}
              </dd>
            </div>
            <!-- 거부 사유·메모·환불 상태(FE-28·Track 80 D-169): 값이 있을 때만 행 노출 -->
            <div v-if="vm.data.rejectReasonCode" class="flex justify-between gap-4">
              <dt class="text-sub">거부 사유</dt>
              <dd class="text-right text-ink" data-testid="claim-reject-reason">{{ vm.claimRejectReasonLabel(vm.data.rejectReasonCode) }}</dd>
            </div>
            <div v-if="vm.data.rejectMemo" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">거부 메모</dt>
              <dd class="whitespace-pre-line text-right text-ink" data-testid="claim-reject-memo">{{ vm.data.rejectMemo }}</dd>
            </div>
            <div v-if="vm.data.refundStatus" class="flex justify-between gap-4">
              <dt class="text-sub">환불 상태</dt>
              <dd class="text-right text-ink" data-testid="claim-refund-status">{{ vm.refundStatusLabel(vm.data.refundStatus) }}</dd>
            </div>
          </dl>
          <!-- 환불 반영 시점 안내(FE-61): 환불이 걸린 클레임에서만·기간은 적지 않는다. -->
          <p v-if="vm.data.refundStatus" class="mt-3 text-xs text-sub" data-testid="claim-refund-timing">{{ vm.REFUND_TIMING_NOTICE }}</p>

          <!-- 첨부 사진(FE-29·Track 81-B): 순서 보존·클릭 시 원본 -->
          <div v-if="vm.data.attachmentUrls && vm.data.attachmentUrls.length > 0" class="mt-4">
            <p class="mb-2 text-sm text-sub">첨부 사진</p>
            <ul class="grid grid-cols-5 gap-2" data-testid="claim-attachments">
              <li v-for="(url, index) in vm.data.attachmentUrls" :key="url" class="aspect-square overflow-hidden rounded-control border border-line">
                <a :href="url" target="_blank" rel="noopener">
                  <img :src="url" :alt="`첨부 사진 ${index + 1}`" class="h-full w-full object-cover" data-testid="claim-attachment-photo">
                </a>
              </li>
            </ul>
          </div>
        </section>

        <!-- 회수 송장 등록(FE-29): 반품 승인 후 구매자가 직접 등록. 등록되면 BE가 returnShipmentRequired=false로 내려 폼이 사라진다. -->
        <section v-if="vm.data.returnShipmentRequired" class="mt-6 rounded-card border border-line p-5" data-testid="claim-return-shipment-form">
          <h2 class="mb-1 text-base font-semibold text-ink">회수 송장 등록</h2>
          <p class="mb-4 text-sm text-sub" data-testid="claim-return-shipment-guide">
            {{ vm.data.claimType === 'EXCHANGE'
              ? '교환할 상품을 발송한 택배사와 송장번호를 등록해 주세요. 쇼핑몰이 회수를 확인하고 검수한 뒤 교환품을 발송합니다.'
              : '상품을 발송한 택배사와 송장번호를 등록해 주세요. 쇼핑몰이 회수를 확인한 뒤 검수를 진행합니다.' }}
          </p>
          <form class="space-y-3" @submit.prevent="vm.submitReturnShipment">
            <div class="space-y-1.5">
              <label for="shipmentCarrier" class="block text-sm font-medium text-ink">택배사</label>
              <select
                id="shipmentCarrier"
                v-model="vm.shipmentCarrier"
                required
                data-testid="claim-return-shipment-carrier"
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
              >
                <option value="" disabled>택배사를 선택하세요</option>
                <option v-for="code in vm.DELIVERY_CARRIER_CODES" :key="code" :value="code">{{ vm.DELIVERY_CARRIER_LABELS[code] }}</option>
              </select>
            </div>
            <div class="space-y-1.5">
              <label for="shipmentTrackingNo" class="block text-sm font-medium text-ink">송장번호</label>
              <input
                id="shipmentTrackingNo"
                v-model="vm.shipmentTrackingNo"
                data-testid="claim-return-shipment-tracking-no"
                type="text"
                :maxlength="vm.DELIVERY_TRACKING_NO_MAX"
                required
                class="w-full rounded-control border border-line px-4 py-2.5 text-sm text-ink transition duration-normal placeholder-gray-400 focus:border-gray-900 focus:outline-hidden focus:ring-1 focus:ring-gray-900"
                placeholder="송장번호"
              >
            </div>
            <p v-if="vm.shipmentError" role="alert" class="text-sm text-soldout" data-testid="claim-return-shipment-error">{{ vm.shipmentError }}</p>
            <Button type="submit" size="lg" class="w-full" :disabled="vm.shipmentSubmitting" data-testid="claim-return-shipment-submit">
              {{ vm.shipmentSubmitting ? '등록 중…' : '회수 송장 등록' }}
            </Button>
          </form>
        </section>

        <!-- 목록으로(FE-63: 주문내역의 자기 유형 탭으로 복귀) -->
        <div class="mt-8">
          <Button variant="outline" size="lg" class="w-full" as-child>
            <NuxtLink :to="{ path: '/orders', query: { tab: vm.listTab } }">주문 내역으로</NuxtLink>
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
