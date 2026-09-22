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
 */
export const WALKTHROUGH_ROOT = resolve(process.cwd(), 'playwright-report/walkthrough')

export interface WalkthroughMetrics {
  role: string
  scenario: string
  title: string
  clicks: number
  inputs: number
  navigations: number
  shots: number
  steps: string[]
  durationMs: number
  recordedAt: string
}

export class Walkthrough {
  private clicks = 0
  private inputs = 0
  private navigations = 0
  private shotIndex = 0
  private readonly steps: string[] = []
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
    const metrics: WalkthroughMetrics = {
      role: this.role,
      scenario: this.scenario,
      title: this.title,
      clicks: this.clicks,
      inputs: this.inputs,
      navigations: this.navigations,
      shots: this.shotIndex,
      steps: this.steps,
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
