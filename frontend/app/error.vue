<script setup lang="ts">
import type { NuxtError } from '#app'
import type { ErrorPageVm } from '~/skins/contracts/error'

// 전역 에러 화면(FE-74). Nuxt는 에러 상태에서 app.vue 대신 이 파일을 렌더하므로 기본 레이아웃(헤더·푸터·data-skin)을 직접 감싼다.
// 404와 그 외(일시 오류) 두 갈래만 구분하고, 이 화면에서는 API를 호출하지 않는다. 상세 화면의 페이지 안 404(CommonErrorState)는 별개다.
const props = defineProps<{ error: NuxtError }>()

const HTTP_NOT_FOUND = 404
// 관리자·셀러 영역은 구매자 헤더·푸터가 보이지 않도록 레이아웃 없이 본문만 렌더하고, "홈으로"는 각 영역 첫 화면으로 보낸다.
const AREA_HOMES = ['/admin', '/seller'] as const
const router = useRouter()
const route = useRoute()

const notFound = computed<boolean>(() => props.error.status === HTTP_NOT_FOUND)
const areaHome = computed<string | null>(
  () => AREA_HOMES.find((prefix) => route.path === prefix || route.path.startsWith(`${prefix}/`)) ?? null,
)

// 관리자·셀러 경로는 기본 레이아웃을 거치지 않아 data-skin이 없다 → renew ErrorView의 [data-skin] 색 변수를 쓰도록 여기서 붙인다(FE-76).
// 에러가 풀려 이 화면이 사라지면 속성도 함께 빠진다. 구매자 경로는 레이아웃이 같은 값을 붙인다.
const skinName = useSkinName()
useHead({ htmlAttrs: { 'data-skin': skinName } })

async function handleGoHome(): Promise<void> {
  await clearError({ redirect: areaHome.value ?? '/' })
}

// 클라이언트 이동이 끝나면 Nuxt 라우터가 에러 상태를 지운다. 주소로 바로 들어와 이전 기록이 없으면 홈으로 보낸다.
async function handleGoBack(): Promise<void> {
  if (window.history.length > 1) {
    router.back()
    return
  }
  await handleGoHome()
}

useSeoMeta({
  title: () => `${notFound.value ? '페이지를 찾을 수 없어요' : '일시적인 오류가 발생했어요'} · zslab-mall`,
})

const vm: ErrorPageVm = reactive({ notFound, handleGoHome, handleGoBack })
</script>

<template>
  <!-- 레이아웃이 없어 바탕 면(LayoutShell의 surface-page)도 없다 → 흰 카드가 묻히지 않게 같은 바탕을 깐다(FE-81). -->
  <div v-if="areaHome" class="min-h-screen bg-surface-page">
    <component :is="useSkinView('ErrorView')" :vm="vm" />
  </div>
  <NuxtLayout v-else name="default">
    <component :is="useSkinView('ErrorView')" :vm="vm" />
  </NuxtLayout>
</template>
