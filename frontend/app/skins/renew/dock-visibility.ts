/**
 * 요소가 화면에 보이는지 관찰한다(FE-71 고정 바 도킹). 화면 아래쪽 bottomInset px(고정 바 자리)는 보이는 영역에서 뺀다 —
 * 실제 버튼이 고정 바 뒤에 있을 때는 "안 보임"으로 판정해 두 버튼이 동시에 보이지 않게 한다. 화면 동작만 하고 데이터·이동은 없다.
 * 반환 함수로 관찰을 끝낸다.
 */
export function observeVisibility(element: HTMLElement, bottomInset: number, onChange: (visible: boolean) => void): () => void {
  const observer = new IntersectionObserver(
    (entries) => {
      const entry = entries[entries.length - 1]
      if (entry) onChange(entry.isIntersecting)
    },
    { rootMargin: `0px 0px -${bottomInset}px 0px` },
  )
  observer.observe(element)
  return () => observer.disconnect()
}
