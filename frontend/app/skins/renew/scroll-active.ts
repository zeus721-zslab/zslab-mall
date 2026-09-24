/**
 * 가로 스크롤 줄에서 현재 항목(aria-current="page")을 보이는 위치(가운데)로 옮긴다(FE-69 보완). 진입 시 한 번,
 * 이후 링크의 aria-current가 바뀔 때마다(같은 뷰에서 경로만 바뀌는 이동) 다시 맞춘다. 화면 동작만 하고 데이터·이동은 없다.
 * 반환 함수로 관찰을 끝낸다. container는 position이 있어야 offsetLeft 기준이 맞다(relative).
 */
export function followActiveItem(container: HTMLElement): () => void {
  function scrollToActive(): void {
    const active = container.querySelector<HTMLElement>('[aria-current="page"]')
    if (!active) return
    container.scrollLeft = active.offsetLeft - (container.clientWidth - active.offsetWidth) / 2
  }
  scrollToActive()
  const observer = new MutationObserver(scrollToActive)
  observer.observe(container, { subtree: true, attributes: true, attributeFilter: ['aria-current'] })
  return () => observer.disconnect()
}
