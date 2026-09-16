/**
 * 관리자 레이아웃 첫 페인트 게이트(FE-22h). 레이아웃 마운트 직후 첫 애니메이션 프레임까지 셸(밴드·사이드바·상단바·콘텐츠) 렌더를 미뤄
 * 모듈 스트리밍·Vuetify 레이아웃 등록(drawer/app-bar) 도중의 중간 상태가 화면에 남지 않게 한다. 게이트 전에는 v-app 배경색만 보인다.
 * Vuetify 자체도 첫 프레임까지 v-main/drawer 트랜지션을 끈다(composables/ssrBoot.js) — 그 프레임 이후 토글 트랜지션은 정상.
 */
export function useFirstPaintGate(): Ref<boolean> {
  const ready = ref<boolean>(false)
  onMounted(() => {
    window.requestAnimationFrame(() => {
      ready.value = true
    })
  })
  return ready
}
