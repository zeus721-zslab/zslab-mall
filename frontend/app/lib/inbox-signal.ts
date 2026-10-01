/**
 * 인박스 변경 신호(SSE · D-249) 공통 순수 로직(FE-103). 레이어 CSS·라우트·API에 묶이지 않는 것만 둔다.
 */

/** 재조회 작업. 실패는 작업 안에서 처리한다(배지 숨김·목록 오류 안내). */
export type RefreshTask = () => Promise<void>

export interface CoalescedRunner {
  /** 작업을 시작한다. 이미 진행 중이면 끝난 뒤 한 번만 더 돌도록 표시하고 진행 중 작업을 기다린다. */
  run(): Promise<void>
}

/**
 * 재조회 합치기: 진행 중에 들어온 신호(여러 번이어도)는 끝난 뒤 1회로 합쳐 다시 돈다. 별도 타이머 디바운스는 두지 않는다 —
 * 배치가 건별 커밋으로 신호를 연발해도 동시에 나가는 재조회는 최대 1건이고, 마지막 신호 뒤의 상태를 반드시 한 번 더 읽는다.
 */
export function createCoalescedRunner(task: RefreshTask): CoalescedRunner {
  let current: Promise<void> | null = null
  let rerunRequested = false

  async function drain(): Promise<void> {
    do {
      rerunRequested = false
      await task()
    } while (rerunRequested)
  }

  function run(): Promise<void> {
    if (current) {
      rerunRequested = true
      return current
    }
    current = drain().finally(() => {
      current = null
    })
    return current
  }

  return { run }
}
