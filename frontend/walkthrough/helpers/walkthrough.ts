import { expect, type Locator, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * 워크스루 계측·스크린샷 헬퍼(Track 98 FE-60).
 *
 * 계측 정의(확정):
 * - clicks  = 사용자 클릭 액션 수. {@link Walkthrough.click}·{@link Walkthrough.check} 호출 1회 = 1클릭.
 * - inputs  = 입력한 필드 수. {@link Walkthrough.fill}·{@link Walkthrough.select} 호출 1회 = 1입력
 *             (Vuetify select는 내부적으로 열기+고르기 2번 클릭하지만 "필드 1개를 채웠다"로 1입력만 센다).
 * - navigations = URL pathname이 바뀐 횟수. page 'framenavigated'(메인 프레임)에서 직전 pathname과 비교하며,
 *             진입 화면(첫 로드)은 세지 않는다. query만 바뀌는 필터 조작은 이동으로 보지 않는다.
 *
 * 래퍼를 거치지 않은 조작(locator.click 직접 호출 등)은 세지 않는다 — 시나리오는 반드시 이 래퍼만 쓴다.
 *
 * Track 99 확장:
 * - segment  = 한 시나리오 안의 구간 계측. 역할 전환 시나리오는 역할별로, 다건 반복 시나리오는 건별로 나눠 센다.
 *              합계(clicks·inputs·navigations)는 구간과 무관하게 시나리오 전체다.
 * - note     = assert하지 않는 관찰값(화면에 무엇이 보였는지·어느 행을 썼는지). 리포트가 사실 근거로 인용한다.
 * - errors   = 시나리오 동안 브라우저가 낸 오류(pageerror·console.error). 통과 여부와 무관하게 사실로 남긴다.
 */
export const WALKTHROUGH_ROOT = resolve(process.cwd(), 'playwright-report/walkthrough')

/** 시나리오 내 구간 계측(역할 전환·다건 반복). */
export interface WalkthroughSegment {
  label: string
  role: string
  clicks: number
  inputs: number
  navigations: number
}

export interface WalkthroughMetrics {
  role: string
  scenario: string
  title: string
  clicks: number
  inputs: number
  navigations: number
  shots: number
  steps: string[]
  segments: WalkthroughSegment[]
  notes: Record<string, string>
  errors: string[]
  durationMs: number
  recordedAt: string
}

export class Walkthrough {
  private clicks = 0
  private inputs = 0
  private navigations = 0
  private shotIndex = 0
  private readonly steps: string[] = []
  private readonly segments: WalkthroughSegment[] = []
  private readonly notes: Record<string, string> = {}
  private readonly errors: string[] = []
  private openSegment: { label: string; role: string; clicks: number; inputs: number; navigations: number } | null = null
  private lastPath: string | null = null
  private readonly startedAt = Date.now()
  private readonly outDir: string

  constructor(
    private readonly page: Page,
    private readonly role: 'admin' | 'seller' | 'buyer',
    private readonly scenario: string,
    private readonly title: string,
  ) {
    this.outDir = resolve(WALKTHROUGH_ROOT, this.role, this.scenario)
    mkdirSync(this.outDir, { recursive: true })
    // 통과한 시나리오에서도 조용히 나는 오류를 놓치지 않도록 기록만 한다(실패시키지 않는다).
    this.page.on('pageerror', (error) => { this.errors.push('pageerror: ' + error.message) })
    this.page.on('console', (message) => {
      if (message.type() !== 'error') return
      // dev 서버 전용 잡음: Nuxt가 dev에서 app manifest(/_nuxt/builds/meta/dev.json)를 찾지 못해 내는 404.
      // 실행마다 나는 시나리오가 달라져 재현성 비교를 깨뜨리므로 제외한다(앱 코드와 무관·리포트에 사실로 기록).
      if (message.text().includes('_nuxt/builds/meta') || message.location().url.includes('/_nuxt/builds/meta/')) return
      this.errors.push('console.error: ' + message.text())
    })
    this.page.on('framenavigated', (frame) => {
      if (frame !== this.page.mainFrame()) return
      let path: string
      try {
        path = new URL(frame.url()).pathname
      } catch {
        return
      }
      if (this.lastPath === null) {
        this.lastPath = path
        return
      }
      if (path !== this.lastPath) {
        this.lastPath = path
        this.navigations += 1
      }
    })
  }

  /** 클릭 1회(버튼·메뉴 항목·링크·행). */
  async click(target: Locator): Promise<void> {
    this.clicks += 1
    await target.click()
  }

  /** 체크박스·라디오 1회(클릭으로 계상). */
  async check(target: Locator): Promise<void> {
    this.clicks += 1
    await target.check()
  }

  /** 텍스트 입력 1필드. */
  async fill(target: Locator, value: string): Promise<void> {
    this.inputs += 1
    await target.fill(value)
  }

  /** 네이티브 select 1필드. */
  async selectOption(target: Locator, value: string): Promise<void> {
    this.inputs += 1
    await target.selectOption(value)
  }

  /**
   * Vuetify v-select 1필드. 활성자를 열고 옵션을 고르는 두 번의 DOM 클릭이 필요하지만 "필드 1개 입력"으로 센다.
   * 목록은 v-overlay(body 직속)에 렌더되므로 page 기준으로 옵션을 찾는다.
   */
  async select(target: Locator, optionLabel: string): Promise<void> {
    this.inputs += 1
    await target.click()
    await this.page.getByRole('option', { name: optionLabel, exact: true }).first().click()
  }

  /**
   * 계측 구간을 연다(이전 구간은 여기서 닫힌다). 역할 전환 시나리오는 역할을, 다건 반복 시나리오는 건 번호를 label로 준다.
   * role을 생략하면 시나리오 기본 역할로 기록한다.
   */
  segment(label: string, role?: 'admin' | 'seller' | 'buyer'): void {
    this.closeSegment()
    this.openSegment = {
      label,
      role: role ?? this.role,
      clicks: this.clicks,
      inputs: this.inputs,
      navigations: this.navigations,
    }
  }

  /** 관찰값 기록(assert 아님). 화면에 무엇이 보였는지·어느 행을 썼는지를 리포트 근거로 남긴다. */
  note(key: string, value: string): void {
    this.notes[key] = value
  }

  /** 열린 구간을 현재 누계와의 차이로 닫는다. */
  private closeSegment(): void {
    const open = this.openSegment
    if (open === null) return
    this.segments.push({
      label: open.label,
      role: open.role,
      clicks: this.clicks - open.clicks,
      inputs: this.inputs - open.inputs,
      navigations: this.navigations - open.navigations,
    })
    this.openSegment = null
  }

  /** 화면 직접 진입(주소 입력). 이동 수는 framenavigated 리스너가 센다. */
  async goto(path: string): Promise<void> {
    await this.page.goto(path)
  }

  /** 단계 스크린샷. 파일명은 NN-단계명.png(번호는 호출 순서). */
  async shot(step: string): Promise<void> {
    this.shotIndex += 1
    this.steps.push(step)
    const name = String(this.shotIndex).padStart(2, '0') + '-' + step + '.png'
    await this.page.screenshot({ path: resolve(this.outDir, name), fullPage: false, animations: 'disabled' })
  }

  /** 시나리오 종료: 계측을 metrics.json으로 남긴다(globalTeardown이 모아 summary를 만든다). */
  finish(): WalkthroughMetrics {
    this.closeSegment()
    const metrics: WalkthroughMetrics = {
      role: this.role,
      scenario: this.scenario,
      title: this.title,
      clicks: this.clicks,
      inputs: this.inputs,
      navigations: this.navigations,
      shots: this.shotIndex,
      steps: this.steps,
      segments: this.segments,
      notes: this.notes,
      errors: this.errors,
      durationMs: Date.now() - this.startedAt,
      recordedAt: new Date().toISOString(),
    }
    writeFileSync(resolve(this.outDir, 'metrics.json'), JSON.stringify(metrics, null, 2) + '\n', 'utf-8')
    return metrics
  }
}

/** 화면이 뜰 때까지 기다린다(컨테이너 testid 기준). */
export async function waitScreen(page: Page, testId: string): Promise<void> {
  await expect(page.getByTestId(testId)).toBeVisible({ timeout: 30_000 })
}
