import { type CoalescedRunner, type RefreshTask, createCoalescedRunner } from '~/lib/inbox-signal'
import { msUntilNextKstMidnight } from '~/lib/inbox-view'
import { useAdminAuthStore } from '#layers/admin/app/stores/adminAuth'

const STREAM_PATH = '/v1/admin/inbox/stream'
const CHANGED_EVENT = 'changed'
/** 연결이 닫힌 뒤(비 200 — 배포 중 502 등) 세션이 남아 있으면 이만큼 쉬고 다시 연결한다. */
const RECONNECT_DELAY_MS = 5000
const MIDNIGHT_SLACK_MS = 1000

/**
 * 관리자 인박스 변경 신호 구독(D-249 · FE-103). 탭당 연결 1개를 모듈에 두고 구독자(사이드바 배지 · 인박스 페이지)가 같은 신호를 받는다.
 * 첫 구독 때 열고 마지막 구독이 풀리면 닫는다 — 사이드바가 관리자 레이아웃 수명 동안 구독하므로 로그아웃(다른 레이아웃으로 이동) 때 닫힌다.
 *
 * 재조회 계기: 변경 신호 · 브라우저 자동 재연결 뒤 open · KST 자정 · 화면 다시 보임(visibilitychange). 구독자마다 재조회는 진행 중이면 끝난 뒤
 * 1회로 합친다. 연결이 CLOSED로 끝나면 재조회 1회로 401 처리를 API 래퍼에 맡기고, 401이 아니면 잠시 뒤 다시 연결하기를 반복한다.
 */
const runners = new Set<CoalescedRunner>()
let streamUrl = ''
let isSignedIn: () => boolean = () => true
let source: EventSource | null = null
let openedBefore = false
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let midnightTimer: ReturnType<typeof setTimeout> | null = null

async function refreshAll(): Promise<void> {
  await Promise.all([...runners].map((runner) => runner.run()))
}

function connect(): void {
  source = new EventSource(streamUrl)
  source.addEventListener('open', onOpen)
  source.addEventListener(CHANGED_EVENT, onChanged)
  source.addEventListener('error', onError)
}

function closeSource(): void {
  if (!source) return
  source.removeEventListener('open', onOpen)
  source.removeEventListener(CHANGED_EVENT, onChanged)
  source.removeEventListener('error', onError)
  source.close()
  source = null
}

/** 첫 open은 구독자가 마운트 때 이미 읽었으므로 넘기고, 재연결 뒤 open만 놓친 신호를 메우려 다시 읽는다. */
function onOpen(): void {
  if (openedBefore) void refreshAll()
  openedBefore = true
}

function onChanged(): void {
  void refreshAll()
}

/**
 * CONNECTING이면 브라우저가 스스로 재연결한다(최대 수명 도달 등). CLOSED만 직접 처리한다: 재조회 1회로 401 처리를 API 래퍼에 맡기고(세션 해제 ·
 * 로그인 이동), 세션이 남아 있으면 재조회 성공·실패와 관계없이 지연 뒤 다시 연결한다 — 배포 중 502처럼 REST까지 잠시 실패해도 신호가 끊기지 않게.
 */
function onError(): void {
  if (!source || source.readyState !== EventSource.CLOSED) return
  closeSource()
  void refreshAll().then(() => {
    if (runners.size === 0 || !isSignedIn()) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (runners.size > 0 && !source) connect()
    }, RECONNECT_DELAY_MS)
  })
}

function scheduleMidnightRefresh(): void {
  midnightTimer = setTimeout(() => {
    void refreshAll()
    scheduleMidnightRefresh()
  }, msUntilNextKstMidnight(Date.now()) + MIDNIGHT_SLACK_MS)
}

function onVisibility(): void {
  if (document.visibilityState === 'visible') void refreshAll()
}

function stop(): void {
  closeSource()
  if (reconnectTimer) clearTimeout(reconnectTimer)
  if (midnightTimer) clearTimeout(midnightTimer)
  reconnectTimer = null
  midnightTimer = null
  openedBefore = false
  document.removeEventListener('visibilitychange', onVisibility)
}

export interface AdminInboxStreamTarget {
  url: string
  /** 관리자 세션이 살아 있는지(401이면 API 래퍼가 해제한다). 닫힌 연결을 다시 열지 판단한다. */
  isSignedIn: () => boolean
}

/** 구독을 등록하고 해제 함수를 돌려준다. 첫 구독이면 연결·자정 타이머·화면 보임 감지를 시작한다. */
export function subscribeAdminInboxStream(target: AdminInboxStreamTarget, task: RefreshTask): () => void {
  const runner = createCoalescedRunner(task)
  runners.add(runner)
  if (runners.size === 1) {
    streamUrl = target.url
    isSignedIn = target.isSignedIn
    connect()
    scheduleMidnightRefresh()
    document.addEventListener('visibilitychange', onVisibility)
  }
  return () => {
    runners.delete(runner)
    if (runners.size === 0) stop()
  }
}

/** 컴포넌트 수명에 맞춰 구독한다(마운트 시 등록 · 언마운트 시 해제). */
export function useAdminInboxStream(task: RefreshTask): void {
  const config = useRuntimeConfig()
  const adminAuth = useAdminAuthStore()
  const target: AdminInboxStreamTarget = {
    url: `${config.public.apiBase || '/api'}${STREAM_PATH}`,
    isSignedIn: () => adminAuth.isAuthenticated,
  }
  let unsubscribe: (() => void) | null = null
  onMounted(() => {
    unsubscribe = subscribeAdminInboxStream(target, task)
  })
  onBeforeUnmount(() => {
    unsubscribe?.()
    unsubscribe = null
  })
}
