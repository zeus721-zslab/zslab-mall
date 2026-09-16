<script setup lang="ts">
import {
  CLAIM_REASON_LABELS,
  claimRejectReasonLabel,
  claimStatusLabel,
  claimTypeLabel,
  refundStatusLabel,
  type ClaimStatus,
} from '~/lib/constants/claim'
import { formatDateTime } from '~/lib/utils/datetime'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const claimPublicId = route.params.claimPublicId as string

const { data, pending, error, refresh } = useClaimDetail(claimPublicId)

// 401(세션 만료)은 /login 유도. 404(타인·미존재)는 존재 은닉이라 안내만(orders/[id] 패턴).
watch(
  error,
  (fetchError) => {
    if ((fetchError as { statusCode?: number } | null)?.statusCode === 401) {
      navigateTo(`/login?redirect=${encodeURIComponent(`/claims/${claimPublicId}`)}`)
    }
  },
  { immediate: true },
)

// 404와 그 외 오류 문구 구분(존재 은닉이라 미노출도 404).
const errorMessage = computed<string>(() =>
  (error.value as { statusCode?: number } | null)?.statusCode === 404
    ? '클레임을 찾을 수 없습니다'
    : '클레임을 불러오지 못했습니다',
)

type StepState = 'done' | 'current' | 'upcoming'
interface TimelineStep {
  label: string
  state: StepState
  at: string | null
}

/**
 * 진행 타임라인(ClaimStatus 4값 기준). 정상 경로는 요청→승인→완료, REJECTED는 요청→거절로 종결한다.
 * processedAt은 전이마다 덮어써지므로(BE Claim.approve/markCompleted/reject) 현재 스텝의 처리 시각으로만 표기한다.
 * 중간 done 스텝은 개별 전이 시각 데이터가 없어 시각을 표기하지 않는다(추정 금지).
 */
const timeline = computed<TimelineStep[]>(() => {
  const detail = data.value
  if (!detail) return []

  if (detail.status === 'REJECTED') {
    return [
      { label: '요청', state: 'done', at: detail.requestedAt },
      { label: '거절', state: 'current', at: detail.processedAt },
    ]
  }

  const order: ClaimStatus[] = ['REQUESTED', 'APPROVED', 'COMPLETED']
  const labels = ['요청', '승인', '완료']
  const currentIndex = order.indexOf(detail.status)
  return order.map((_, index) => ({
    label: labels[index] as string,
    state: index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'upcoming',
    at: index === 0 ? detail.requestedAt : index === currentIndex ? detail.processedAt : null,
  }))
})

function stepCircleClass(state: StepState): string {
  if (state === 'current') return 'bg-primary text-white ring-2 ring-primary ring-offset-2'
  if (state === 'done') return 'bg-primary text-white'
  return 'bg-gray-100 text-sub'
}

useSeoMeta({ title: '클레임 상세 · zslab-mall', description: 'zslab-mall 클레임 상세' })
</script>

<template>
  <div class="py-8 md:py-12">
    <div class="mx-auto max-w-[880px] px-4 md:px-6">
      <!-- 로딩 -->
      <div v-if="pending" class="space-y-4">
        <div class="h-8 w-2/3 animate-pulse rounded bg-gray-100"></div>
        <div class="h-40 animate-pulse rounded-card bg-gray-100"></div>
      </div>

      <!-- 에러 / 없음(404 포함) -->
      <CommonErrorState v-else-if="error || !data" :message="errorMessage" @retry="refresh" />

      <!-- 상세 -->
      <template v-else>
        <!-- 헤더: 클레임 유형 + 상태 -->
        <div class="mb-6 flex items-start justify-between gap-4">
          <div class="min-w-0">
            <p class="text-sm text-sub">클레임 유형</p>
            <h1 class="mt-1 text-lg font-medium text-ink">{{ claimTypeLabel(data.claimType) }}</h1>
          </div>
          <span class="shrink-0 rounded-badge bg-gray-100 px-3 py-1 text-sm font-medium text-ink">
            {{ claimStatusLabel(data.status) }}
          </span>
        </div>

        <!-- 진행 타임라인 -->
        <section class="rounded-card border border-line p-5">
          <h2 class="mb-5 text-base font-semibold text-ink">진행 상태</h2>
          <ol class="flex items-start">
            <template v-for="(step, index) in timeline" :key="step.label">
              <li class="flex min-w-[3rem] flex-col items-center gap-1.5 text-center">
                <span
                  class="flex h-8 w-8 items-center justify-center rounded-full text-xs font-semibold"
                  :class="stepCircleClass(step.state)"
                >
                  {{ index + 1 }}
                </span>
                <span
                  class="text-xs"
                  :class="step.state === 'upcoming' ? 'text-sub' : 'font-medium text-ink'"
                >
                  {{ step.label }}
                </span>
                <span v-if="step.at" class="text-[11px] text-sub">{{ formatDateTime(step.at) }}</span>
              </li>
              <!-- 연결선: 이전 스텝을 통과(done)했으면 강조. 원 중심 높이에 맞춰 정렬. -->
              <span
                v-if="index < timeline.length - 1"
                class="mx-1 mt-4 h-0.5 flex-1"
                :class="step.state === 'done' ? 'bg-primary' : 'bg-line'"
              ></span>
            </template>
          </ol>
        </section>

        <!-- 클레임 정보 -->
        <section class="mt-6 rounded-card border border-line p-5">
          <h2 class="mb-3 text-base font-semibold text-ink">클레임 정보</h2>
          <dl class="space-y-3 text-sm">
            <div class="flex justify-between gap-4">
              <dt class="text-sub">사유</dt>
              <dd class="text-right text-ink">{{ CLAIM_REASON_LABELS[data.reasonCode] }}</dd>
            </div>
            <div v-if="data.reasonDetail" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">상세 사유</dt>
              <dd class="whitespace-pre-line text-right text-ink">{{ data.reasonDetail }}</dd>
            </div>
            <div class="flex justify-between gap-4">
              <dt class="text-sub">요청 일시</dt>
              <dd class="text-right text-ink">{{ formatDateTime(data.requestedAt) }}</dd>
            </div>
            <div v-if="data.processedAt" class="flex justify-between gap-4">
              <dt class="text-sub">처리 일시</dt>
              <dd class="text-right text-ink">{{ formatDateTime(data.processedAt) }}</dd>
            </div>
            <!-- 거부 사유·메모·환불 상태(FE-28·Track 80 D-169): 값이 있을 때만 행 노출 -->
            <div v-if="data.rejectReasonCode" class="flex justify-between gap-4">
              <dt class="text-sub">거부 사유</dt>
              <dd class="text-right text-ink" data-testid="claim-reject-reason">{{ claimRejectReasonLabel(data.rejectReasonCode) }}</dd>
            </div>
            <div v-if="data.rejectMemo" class="flex justify-between gap-4">
              <dt class="shrink-0 text-sub">거부 메모</dt>
              <dd class="whitespace-pre-line text-right text-ink" data-testid="claim-reject-memo">{{ data.rejectMemo }}</dd>
            </div>
            <div v-if="data.refundStatus" class="flex justify-between gap-4">
              <dt class="text-sub">환불 상태</dt>
              <dd class="text-right text-ink" data-testid="claim-refund-status">{{ refundStatusLabel(data.refundStatus) }}</dd>
            </div>
          </dl>
        </section>

        <!-- 목록으로 -->
        <div class="mt-8">
          <Button variant="outline" size="lg" class="w-full" as-child>
            <NuxtLink to="/claims">클레임 내역으로</NuxtLink>
          </Button>
        </div>
      </template>
    </div>
  </div>
</template>
