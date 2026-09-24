<script setup lang="ts">
import type { ClaimDetailPageVm } from '~/skins/contracts/claim-detail'
import MypageFrame from '../components/MypageFrame.vue'
import RenewNotice from '../components/RenewNotice.vue'
import { CLAIM_NEUTRAL_CHIP_CLASS, CLAIM_TYPE_BADGE_CLASS } from '../claim-type-tone'

// renew 클레임 상세(FE-73). 화면 상태·동작은 classic과 같다: 헤더(유형·상태) → 요청 취소(접수 상태만·인라인 확인) → 진행 타임라인 →
// 클레임 정보(회수·검수·재발송·거부·환불·첨부) → 회수 송장 등록(필요할 때만) → 목록 링크. 문구·testid는 페이지 vm 그대로다.
// 타임라인은 ≥768 가로, <768 세로로 놓는다(단계 수 최대 7).
defineProps<{ vm: ClaimDetailPageVm }>()

const CARD = 'rounded-[28px] bg-white p-6 md:p-8'
const LABEL = 'mb-1.5 block text-sm font-bold text-ink'
const FIELD =
  'h-12 w-full rounded-[14px] border border-line bg-white px-4 text-sm text-ink transition duration-200 placeholder:text-sub focus:border-primary focus:outline-hidden focus:ring-1 focus:ring-primary'
const SMALL_PILL =
  'flex min-h-10 items-center justify-center rounded-full px-4 text-sm font-bold transition duration-200 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40'
const ROW = 'flex justify-between gap-4'
</script>

<template>
  <MypageFrame title="클레임 상세" active-to="/orders">
    <!-- 로딩 -->
    <div v-if="vm.pending" class="space-y-4" aria-hidden="true">
      <div :class="[CARD, 'h-28']"></div>
      <div :class="[CARD, 'h-48']"></div>
    </div>

    <!-- 에러 / 없음(404 포함) -->
    <CommonErrorState v-else-if="vm.error || !vm.data" :message="vm.errorMessage" @retry="vm.refresh" />

    <div v-else class="space-y-6">
      <!-- 헤더: 클레임 유형 + 상태 · 요청 취소(Track 101-A·접수 상태만) -->
      <section :class="CARD" aria-label="클레임 요약">
        <div class="flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-xs text-sub">클레임 유형</p>
            <!-- 유형 = 색 배지 · 상태 = 중립 칩(FE-73 보완 1) -->
            <span :class="['mt-2 inline-flex rounded-full px-4 py-1.5 text-base font-bold', CLAIM_TYPE_BADGE_CLASS[vm.data.claimType]]" data-testid="claim-type-badge">
              {{ vm.claimTypeLabel(vm.data.claimType) }}
            </span>
          </div>
          <span :class="['shrink-0 rounded-full px-3 py-1 text-sm font-bold', CLAIM_NEUTRAL_CHIP_CLASS]">{{ vm.claimStatusLabel(vm.data.status) }}</span>
        </div>

        <div v-if="vm.cancellable" class="mt-5" data-testid="claim-cancel-block">
          <button
            v-if="!vm.cancelConfirmOpen"
            type="button"
            :class="[SMALL_PILL, 'border border-line bg-white text-ink hover:border-ink']"
            data-testid="claim-cancel-open"
            @click="vm.cancelConfirmOpen = true"
          >
            요청 취소
          </button>
          <!-- 요청 취소 확인은 인라인 그대로(모달 전환은 이월). -->
          <RenewNotice v-else tone="warning" data-testid="claim-cancel-panel">
            <p class="text-ink">{{ vm.claimTypeLabel(vm.data.claimType) }} 요청을 취소할까요?</p>
            <p class="mt-1 font-normal" style="white-space: pre-line" data-testid="claim-cancel-warning">{{ vm.CLAIM_CANCEL_WARNING }}</p>
            <template #action>
              <button
                type="button"
                class="btn btn-danger btn-sm max-md:min-h-11"
                :disabled="vm.cancelSubmitting"
                data-testid="claim-cancel-submit"
                @click="vm.submitCancel"
              >
                {{ vm.cancelSubmitting ? '취소 중…' : '요청 취소' }}
              </button>
              <button
                type="button"
                class="btn btn-sm bg-white text-primary max-md:min-h-11"
                :disabled="vm.cancelSubmitting"
                data-testid="claim-cancel-dismiss"
                @click="vm.cancelConfirmOpen = false"
              >
                닫기
              </button>
            </template>
          </RenewNotice>
          <RenewNotice v-if="vm.cancelError" tone="danger" class="mt-3" data-testid="claim-cancel-error">{{ vm.cancelError }}</RenewNotice>
        </div>
        <RenewNotice v-else-if="vm.cancelError" tone="danger" class="mt-5" data-testid="claim-cancel-error">{{ vm.cancelError }}</RenewNotice>
      </section>

      <!-- 진행 타임라인 -->
      <section :class="CARD" aria-labelledby="claim-timeline-title">
        <h2 id="claim-timeline-title" class="text-lg font-bold text-ink">진행 상태</h2>
        <ol class="mt-6 flex flex-col md:flex-row" data-testid="claim-timeline">
          <li
            v-for="(step, index) in vm.timeline"
            :key="step.label"
            class="relative flex gap-3 pb-6 last:pb-0 md:flex-1 md:flex-col md:items-center md:gap-2 md:pb-0 md:text-center"
            data-testid="claim-timeline-step"
          >
            <!-- 다음 단계까지 잇는 선: 이 단계를 지났으면(done) 포인트색. <768 세로 · ≥768 가로(원 중심 기준). -->
            <span
              v-if="index < vm.timeline.length - 1"
              :class="[
                'absolute left-4 top-8 h-full w-0.5 -translate-x-1/2 md:left-1/2 md:top-4 md:h-0.5 md:w-full md:translate-x-0 md:-translate-y-1/2',
                step.state === 'done' ? 'bg-primary' : 'bg-line',
              ]"
              aria-hidden="true"
            ></span>
            <span
              :class="[
                'relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full font-mono text-xs font-semibold transition duration-300',
                step.state === 'upcoming' ? 'bg-surface-muted text-sub' : 'bg-primary text-primary-foreground',
                step.state === 'current' ? 'ring-4 ring-(--pastel-lavender-bg)' : '',
              ]"
            >
              <svg v-if="step.state === 'done'" class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M5 12.5l4.5 4.5L19 7.5" />
              </svg>
              <template v-else>{{ index + 1 }}</template>
            </span>
            <div class="min-w-0 pt-1 md:pt-0">
              <p :class="['text-sm', step.state === 'upcoming' ? 'text-sub' : 'font-bold text-ink']">{{ step.label }}</p>
              <p v-if="step.at" class="mt-0.5 font-mono text-[11px] text-sub">{{ vm.formatDateTime(step.at) }}</p>
            </div>
          </li>
        </ol>
        <RenewNotice tone="info" class="mt-6" data-testid="claim-stage-guide">{{ vm.stageGuide }}</RenewNotice>
      </section>

      <!-- 클레임 정보 -->
      <section :class="CARD" aria-labelledby="claim-info-title">
        <h2 id="claim-info-title" class="text-lg font-bold text-ink">클레임 정보</h2>
        <dl class="mt-4 space-y-3 text-sm">
          <div :class="ROW">
            <dt class="shrink-0 text-sub">사유</dt>
            <dd class="text-right text-ink">{{ vm.CLAIM_REASON_LABELS[vm.data.reasonCode] }}</dd>
          </div>
          <div v-if="vm.data.reasonDetail" :class="ROW">
            <dt class="shrink-0 text-sub">상세 사유</dt>
            <dd class="whitespace-pre-line text-right text-ink">{{ vm.data.reasonDetail }}</dd>
          </div>
          <!-- 교환 옵션(FE-30·D-177): EXCHANGE만·라벨이 둘 다 없으면 행 숨김 -->
          <div v-if="vm.data.claimType === 'EXCHANGE' && (vm.data.originalOptionLabel || vm.data.exchangeOptionLabel)" :class="ROW">
            <dt class="shrink-0 text-sub">교환 옵션</dt>
            <dd class="text-right text-ink" data-testid="claim-exchange-option">{{ vm.data.originalOptionLabel ?? '—' }} → {{ vm.data.exchangeOptionLabel ?? '—' }}</dd>
          </div>
          <div :class="ROW">
            <dt class="shrink-0 text-sub">요청 일시</dt>
            <dd class="text-right font-mono text-ink">{{ vm.formatDateTime(vm.data.requestedAt) }}</dd>
          </div>
          <div v-if="vm.data.processedAt" :class="ROW">
            <dt class="shrink-0 text-sub">처리 일시</dt>
            <dd class="text-right font-mono text-ink">{{ vm.formatDateTime(vm.data.processedAt) }}</dd>
          </div>
          <!-- 반품 회수·검수·재발송(FE-29·Track 81-A): 값이 있을 때만 행 노출 -->
          <div v-if="vm.data.returnShipment" :class="ROW">
            <dt class="shrink-0 text-sub">회수 송장</dt>
            <dd class="text-right text-ink" data-testid="claim-return-shipment">{{ vm.deliveryCarrierLabel(vm.data.returnShipment.carrier) }} {{ vm.data.returnShipment.trackingNo }}</dd>
          </div>
          <div v-if="vm.data.pickedUpAt" :class="ROW">
            <dt class="shrink-0 text-sub">회수 확인</dt>
            <dd class="text-right font-mono text-ink" data-testid="claim-picked-up-at">{{ vm.formatDateTime(vm.data.pickedUpAt) }}</dd>
          </div>
          <div v-if="vm.data.inspectionResult" :class="ROW">
            <dt class="shrink-0 text-sub">검수 결과</dt>
            <dd class="text-right text-ink" data-testid="claim-inspection-result">{{ vm.CLAIM_INSPECTION_RESULT_LABELS[vm.data.inspectionResult] }}</dd>
          </div>
          <div v-if="vm.data.reshipment" :class="ROW">
            <dt class="shrink-0 text-sub" data-testid="claim-reshipment-label">{{ vm.data.claimType === 'EXCHANGE' ? '교환품 배송 송장' : '재발송 송장' }}</dt>
            <dd class="text-right text-ink" data-testid="claim-reshipment">{{ vm.deliveryCarrierLabel(vm.data.reshipment.carrier) }} {{ vm.data.reshipment.trackingNo }}</dd>
          </div>
          <!-- 거부 사유·메모·환불 상태(FE-28·Track 80 D-169): 값이 있을 때만 행 노출 -->
          <div v-if="vm.data.rejectReasonCode" :class="ROW">
            <dt class="shrink-0 text-sub">거부 사유</dt>
            <dd class="text-right text-ink" data-testid="claim-reject-reason">{{ vm.claimRejectReasonLabel(vm.data.rejectReasonCode) }}</dd>
          </div>
          <div v-if="vm.data.rejectMemo" :class="ROW">
            <dt class="shrink-0 text-sub">거부 메모</dt>
            <dd class="whitespace-pre-line text-right text-ink" data-testid="claim-reject-memo">{{ vm.data.rejectMemo }}</dd>
          </div>
          <div v-if="vm.data.refundStatus" :class="ROW">
            <dt class="shrink-0 text-sub">환불 상태</dt>
            <dd class="text-right" data-testid="claim-refund-status">
              <span :class="['inline-flex rounded-full px-2.5 py-0.5 text-xs font-bold', CLAIM_NEUTRAL_CHIP_CLASS]">{{ vm.refundStatusLabel(vm.data.refundStatus) }}</span>
            </dd>
          </div>
        </dl>
        <!-- 환불 반영 시점 안내(FE-61): 환불이 걸린 클레임에서만·기간은 적지 않는다. -->
        <RenewNotice v-if="vm.data.refundStatus" tone="info" class="mt-4" data-testid="claim-refund-timing">{{ vm.REFUND_TIMING_NOTICE }}</RenewNotice>

        <!-- 첨부 사진(FE-29·Track 81-B): 순서 보존·클릭 시 원본 -->
        <div v-if="vm.data.attachmentUrls && vm.data.attachmentUrls.length > 0" class="mt-5">
          <p class="mb-2 text-sm text-sub">첨부 사진</p>
          <ul class="grid grid-cols-4 gap-2 sm:grid-cols-5" data-testid="claim-attachments">
            <li v-for="(url, index) in vm.data.attachmentUrls" :key="url" class="aspect-square overflow-hidden rounded-[14px] bg-(--image-placeholder)">
              <a :href="url" target="_blank" rel="noopener" class="group block h-full w-full">
                <img
                  :src="url"
                  :alt="`첨부 사진 ${index + 1}`"
                  class="h-full w-full object-cover transition duration-500 ease-out motion-safe:group-hover:scale-[1.04]"
                  data-testid="claim-attachment-photo"
                >
              </a>
            </li>
          </ul>
        </div>
      </section>

      <!-- 회수 송장 등록(FE-29): 승인 후 구매자가 직접 등록. 등록되면 BE가 returnShipmentRequired=false로 내려 폼이 사라진다. -->
      <section v-if="vm.data.returnShipmentRequired" :class="CARD" data-testid="claim-return-shipment-form" aria-labelledby="claim-shipment-title">
        <h2 id="claim-shipment-title" class="text-lg font-bold text-ink">회수 송장 등록</h2>
        <p class="mt-1 break-keep text-sm text-sub" data-testid="claim-return-shipment-guide">
          {{ vm.data.claimType === 'EXCHANGE'
            ? '교환할 상품을 발송한 택배사와 송장번호를 등록해 주세요. 쇼핑몰이 회수를 확인하고 검수한 뒤 교환품을 발송합니다.'
            : '상품을 발송한 택배사와 송장번호를 등록해 주세요. 쇼핑몰이 회수를 확인한 뒤 검수를 진행합니다.' }}
        </p>
        <form class="mt-5 grid gap-4 sm:grid-cols-2" @submit.prevent="vm.submitReturnShipment">
          <div>
            <label for="shipmentCarrier" :class="LABEL">택배사</label>
            <select id="shipmentCarrier" v-model="vm.shipmentCarrier" required data-testid="claim-return-shipment-carrier" :class="FIELD">
              <option value="" disabled>택배사를 선택하세요</option>
              <option v-for="code in vm.DELIVERY_CARRIER_CODES" :key="code" :value="code">{{ vm.DELIVERY_CARRIER_LABELS[code] }}</option>
            </select>
          </div>
          <div>
            <label for="shipmentTrackingNo" :class="LABEL">송장번호</label>
            <input
              id="shipmentTrackingNo"
              v-model="vm.shipmentTrackingNo"
              data-testid="claim-return-shipment-tracking-no"
              type="text"
              :maxlength="vm.DELIVERY_TRACKING_NO_MAX"
              required
              :class="FIELD"
              placeholder="송장번호"
            >
          </div>
          <RenewNotice v-if="vm.shipmentError" tone="danger" class="sm:col-span-2" data-testid="claim-return-shipment-error">{{ vm.shipmentError }}</RenewNotice>
          <button
            type="submit"
            class="flex h-14 w-full items-center justify-center gap-2 rounded-full bg-primary text-base font-bold text-primary-foreground transition duration-200 hover:bg-primary-hover focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 disabled:cursor-default disabled:opacity-40 disabled:hover:bg-primary sm:col-span-2"
            :disabled="vm.shipmentSubmitting"
            data-testid="claim-return-shipment-submit"
          >
            <span v-if="vm.shipmentSubmitting" class="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white motion-reduce:animate-none" aria-hidden="true"></span>
            {{ vm.shipmentSubmitting ? '등록 중…' : '회수 송장 등록' }}
          </button>
        </form>
      </section>

      <!-- 목록으로(FE-73: 주문 내역의 취소·반품·교환 탭으로 복귀) -->
      <NuxtLink
        :to="{ path: '/orders', query: { tab: vm.listTab } }"
        class="flex min-h-14 w-full items-center justify-center rounded-full border border-line bg-white text-base font-bold text-ink transition duration-200 hover:border-ink focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-primary"
      >
        주문 내역으로
      </NuxtLink>
    </div>
  </MypageFrame>
</template>
