/** 가로 스크롤 줄의 양끝 도달 여부. atStart·atEnd로 이동 버튼 비활성·오른쪽 흐림 표시를 정한다. */
export interface ScrollEdges {
  atStart: boolean
  atEnd: boolean
}

// 스크롤 위치 판정 여유(소수점 픽셀 오차).
const EDGE_TOLERANCE = 1

export function readScrollEdges(metrics: { scrollLeft: number; clientWidth: number; scrollWidth: number }): ScrollEdges {
  return {
    atStart: metrics.scrollLeft <= EDGE_TOLERANCE,
    atEnd: metrics.scrollLeft + metrics.clientWidth >= metrics.scrollWidth - EDGE_TOLERANCE,
  }
}

/**
 * 스크롤 줄 요소의 양끝 상태를 스크롤·창 크기 변화마다 갱신한다(FE-77). 화면 상태만 다루고 데이터·이동은 없다.
 * 줄은 데이터가 온 뒤 생길 수 있어(클라이언트 이동) 요소가 생길 때도 판정한다. 측정 전(SSR 포함)은 "시작·끝 아님" = 흐림 표시.
 */
export function trackScrollEdges(element: Readonly<Ref<HTMLElement | null>>): Readonly<Ref<ScrollEdges>> {
  const edges = ref<ScrollEdges>({ atStart: true, atEnd: false })

  function update(): void {
    if (element.value) edges.value = readScrollEdges(element.value)
  }

  watch(
    element,
    (current, previous) => {
      previous?.removeEventListener('scroll', update)
      current?.addEventListener('scroll', update, { passive: true })
      update()
    },
    { flush: 'post', immediate: true },
  )
  onMounted(() => {
    update()
    window.addEventListener('resize', update)
  })
  onBeforeUnmount(() => {
    element.value?.removeEventListener('scroll', update)
    window.removeEventListener('resize', update)
  })

  return readonly(edges)
}
