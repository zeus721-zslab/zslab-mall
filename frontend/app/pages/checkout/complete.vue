<script setup lang="ts">
import type { CheckoutCompletePageVm } from '~/skins/contracts/checkout-complete'

// BUYER 전용 — 미인증/비-BUYER는 buyer 미들웨어가 /login으로 유도한다.
definePageMeta({ middleware: 'buyer' })

const route = useRoute()
const cart = useCartStore()

// 결제 완료 후 mock 페이지가 관통시킨 주문번호. 직접 진입 등으로 없으면 주문번호만 생략한다(에러 아님).
const orderPublicId = String(route.query.orderPublicId ?? '')

// 완료 화면 진입 시 카트 서버 상태를 재조회한다. SUCCESS webhook의 AFTER_COMMIT 소진이 완료화면 진입보다
// 선행하므로(BE 정찰 확정·recon-report-fe-11-be-event) 이 시점 재조회는 비워진 카트를 반영한다.
// load 실패는 완료 안내를 막지 않는다(뱃지 정합만 지연·치명 아님·흡수).
onMounted(async () => {
  try {
    await cart.load()
  } catch {
    // 카트 재조회 실패는 주문 완료 표시에 영향 주지 않는다.
  }
})

// 화면에는 사람이 읽는 주문번호만 보인다(Track 105-4g-3): 쿼리의 내부 id로 주문 상세를 조회해 orderNo를 쓴다.
// id가 없으면 조회하지 않고, 조회 실패는 번호 줄만 생략한다(완료 안내·상세 링크는 그대로).
const orderDetail = orderPublicId ? useOrderDetail(orderPublicId) : null
const orderNo = computed<string>(() => orderDetail?.data.value?.orderNo ?? '')

useSeoMeta({ title: '주문 완료 · zslab-mall' })

const vm: CheckoutCompletePageVm = reactive({ orderPublicId, orderNo })
</script>

<template>
  <component :is="useSkinView('CheckoutCompleteView')" :vm="vm" />
</template>
