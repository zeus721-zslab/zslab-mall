import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { Locator, Page } from '@playwright/test'
import type { ManualCaptureIndex, ManualRegion, ManualRole } from '../../app/types/manual'
import { privacySelectorsFor } from './privacy'

/**
 * 매뉴얼 캡처 헬퍼(C8 P1 · 워크스루 체계 확장). 단계마다: 안정 대기 → 개인정보 blur 주입 → 1440×900 캡처 → WebP 변환 →
 * 콜아웃 대상(data-testid)의 boundingBox를 이미지 좌표로 public/manual/{역할}/captures.json에 기록한다.
 *
 * - 1440×900 뷰포트에서 사이드바 오른쪽 본문 영역만 자른다(contentClip) · deviceScaleFactor 1 → 이미지 좌표 = boundingBox − clip 원점.
 * - WebP 변환은 같은 브라우저의 canvas 인코더로 한다(새 의존성 없음 · Chromium은 image/webp 인코딩 지원).
 * - captures.json은 캡처마다 병합 저장한다 — 한 단계가 실패해도 앞 단계 결과가 남는다.
 */
export const MANUAL_OUTPUT_ROOT = resolve(process.cwd(), 'public/manual')
const WEBP_QUALITY = 0.82
const PRIVACY_STYLE_ID = 'manual-capture-privacy'
const BLUR_RADIUS_PX = 6
/**
 * 캡처에서 숨기는 요소. Nuxt DevTools는 dev 서버 전용이다. 데모 로그인 버튼은 서버 데모 설정(NUXT_*_DEMO_*)이 비어 있으면
 * 아예 표시되지 않는 버튼이라(layers/admin/nuxt.config.ts runtimeConfig 기본 '' = 미표시), 숨긴 화면이 실운영 로그인 화면과 같다.
 */
const HIDDEN_SELECTORS = ['#nuxt-devtools-container', '#nuxt-devtools-anchor', '[data-testid="admin-demo-login"]', '[data-testid="seller-demo-login"]']

/**
 * 캡처 대상 행 선택 기준(C8 P2): 시드 데이터 행만 쓴다. 개발·검증 중에 남은 행(검증 메모 문구 · 워크스루 송장 접두 WT/WTA/WTS/WTM ·
 * 워크스루 계정)은 시드가 만들지 않은 데이터라 매뉴얼 캡처에 쓰지 않는다. 시나리오는 행을 고를 때 이 패턴을 hasNotText로 건다.
 */
export const DEV_RESIDUE_PATTERN = /STEP \d+|검토용|라이브검증|워크스루|walkthrough|\bWT[A-Z]?\d/

/** 시드 계정 이메일 도메인(scripts/demo-seed/seed.py:41 DEMO_EMAIL_DOMAIN). 회원·셀러 목록은 이 도메인 행만 쓴다. */
const SEED_ACCOUNT_DOMAIN = '@demo.zslab-mall.com'

/** 표·목록 행 중 시드 데이터 행만 남긴다(DEV_RESIDUE_PATTERN 참고). */
export function seedRows(rows: Locator): Locator {
  return rows.filter({ hasNotText: DEV_RESIDUE_PATTERN })
}

/** 회원·셀러 목록에서 시드 계정 행만 남긴다(시드 밖 실가입·검증 계정 제외). */
export function seedAccountRows(rows: Locator): Locator {
  return seedRows(rows).filter({ hasText: SEED_ACCOUNT_DOMAIN })
}

interface CaptureClip {
  x: number
  y: number
  width: number
  height: number
}

export interface ShotOptions {
  /** 지정하면 이 요소 둘레만 자른다(다이얼로그 단계). */
  focus?: Locator
  /** true면 사이드바까지 뷰포트 전체를 찍는다(화면 구성 안내 단계). */
  fullViewport?: boolean
}

/** 확대 캡처에서 대상 요소 바깥으로 남기는 여백(px). 창이 화면 어디에 떠 있는지 알 만큼만 남긴다. */
const FOCUS_MARGIN_PX = 40

export class ManualCapture {
  private readonly outDir: string
  private readonly indexFile: string

  constructor(private readonly page: Page, private readonly role: ManualRole) {
    this.outDir = resolve(MANUAL_OUTPUT_ROOT, role)
    this.indexFile = resolve(this.outDir, 'captures.json')
    mkdirSync(this.outDir, { recursive: true })
  }

  /** 캡처 1장. regions는 콘텐츠 콜아웃이 참조하는 영역 키 → 대상 요소. 보이지 않는 대상은 실패시킨다(빈 마커 방지). */
  async shot(captureId: string, regions: Record<string, Locator>, options: ShotOptions = {}): Promise<void> {
    await this.settle()
    const masked = await this.applyPrivacy()
    const clip = await this.resolveClip(captureId, options)
    const measured: Record<string, ManualRegion> = {}
    for (const [key, locator] of Object.entries(regions)) {
      measured[key] = await this.measure(captureId, key, locator, clip)
    }
    const png = await this.page.screenshot({ clip, animations: 'disabled', caret: 'hide' })
    const webp = await this.toWebp(png)
    const file = captureId + '.webp'
    writeFileSync(resolve(this.outDir, file), webp)
    const index = this.readIndex()
    index[captureId] = {
      file,
      width: clip.width,
      height: clip.height,
      bytes: webp.length,
      hash: createHash('sha256').update(webp).digest('hex').slice(0, 10),
      regions: measured,
    }
    writeFileSync(this.indexFile, JSON.stringify(index, null, 2) + '\n', 'utf-8')
    console.log(`[manual] ${this.role}/${file} ${webp.length}B · 영역 ${Object.keys(measured).length} · 가림 요소 ${masked}`)
  }

  /**
   * 글꼴·전환 애니메이션이 끝날 때까지 기다리고, 포인터를 화면 구석으로 치워 hover 흔적을 없앤다.
   * 무한 반복 애니메이션(입력 커서·로딩 표시 등)은 끝나지 않으므로 유한 애니메이션만 기다린다.
   */
  private async settle(): Promise<void> {
    await this.page.evaluate(async () => { await document.fonts.ready })
    await this.page.mouse.move(1, 899)
    await this.page.waitForFunction(() => document.getAnimations().every((animation) => {
      const iterations = animation.effect === null ? 1 : animation.effect.getTiming().iterations
      return iterations === Infinity || animation.playState !== 'running'
    }))
  }

  /** 개인정보 blur 주입(경로별 규칙 포함). 반환값 = 지금 화면에서 가려진 요소 수(보고용). */
  private async applyPrivacy(): Promise<number> {
    const selectors = privacySelectorsFor(new URL(this.page.url()).pathname)
    const css = selectors.join(',\n') + ` { filter: blur(${BLUR_RADIUS_PX}px) !important; }\n`
      + `${HIDDEN_SELECTORS.join(', ')} { display: none !important; }`
    return this.page.evaluate(({ id, content, joined }) => {
      let style = document.getElementById(id)
      if (style === null) {
        style = document.createElement('style')
        style.id = id
        document.head.appendChild(style)
      }
      style.textContent = content
      return document.querySelectorAll(joined).length
    }, { id: PRIVACY_STYLE_ID, content: css, joined: selectors.join(',') })
  }

  /**
   * 캡처 범위 = 앱 사이드바 오른쪽 본문 영역(v-main의 왼쪽 여백 = 사이드바 폭). 매뉴얼 본문 폭에서 사이드바까지 담으면
   * 화면이 작게 줄어 글자를 읽기 어렵다 — 어느 메뉴인지는 본문 설명과 상단 브레드크럼으로 알린다.
   */
  private async contentClip(): Promise<CaptureClip> {
    const viewport = this.page.viewportSize()
    if (viewport === null) throw new Error('뷰포트 크기를 알 수 없습니다')
    const sidebarWidth = await this.page.evaluate(() => {
      const main = document.querySelector('.v-main')
      return main === null ? 0 : Number.parseFloat(getComputedStyle(main).paddingLeft) || 0
    })
    const left = Math.min(Math.round(sidebarWidth), viewport.width - 1)
    return { x: left, y: 0, width: viewport.width - left, height: viewport.height }
  }

  private async resolveClip(captureId: string, options: ShotOptions): Promise<CaptureClip> {
    if (options.focus !== undefined) return this.focusClip(captureId, options.focus)
    if (options.fullViewport === true) {
      const viewport = this.page.viewportSize()
      if (viewport === null) throw new Error('뷰포트 크기를 알 수 없습니다')
      return { x: 0, y: 0, width: viewport.width, height: viewport.height }
    }
    return this.contentClip()
  }

  /**
   * 다이얼로그처럼 화면 일부만 설명하는 단계: 대상 요소 둘레(여백 포함)만 자른다. 전체 화면으로 찍으면 어두운 배경이 대부분이라
   * 정작 창이 작게 보인다. 범위는 본문 영역(contentClip) 안으로 자른다.
   */
  private async focusClip(captureId: string, focus: Locator): Promise<CaptureClip> {
    const content = await this.contentClip()
    const box = await focus.boundingBox()
    if (box === null) throw new Error(`${captureId}: 확대 대상이 화면에 없습니다`)
    const left = Math.max(content.x, Math.round(box.x - FOCUS_MARGIN_PX))
    const top = Math.max(content.y, Math.round(box.y - FOCUS_MARGIN_PX))
    const right = Math.min(content.x + content.width, Math.round(box.x + box.width + FOCUS_MARGIN_PX))
    const bottom = Math.min(content.y + content.height, Math.round(box.y + box.height + FOCUS_MARGIN_PX))
    return { x: left, y: top, width: right - left, height: bottom - top }
  }

  /** 대상 boundingBox(뷰포트 좌표) → 캡처 이미지 좌표(clip 기준)로 옮기고 이미지 안으로 자른다. */
  private async measure(captureId: string, key: string, locator: Locator, clip: CaptureClip): Promise<ManualRegion> {
    const box = await locator.boundingBox()
    if (box === null) throw new Error(`${captureId}: 영역 ${key} 대상이 화면에 없습니다`)
    const left = Math.max(0, Math.round(box.x - clip.x))
    const top = Math.max(0, Math.round(box.y - clip.y))
    const right = Math.min(clip.width, Math.round(box.x - clip.x + box.width))
    const bottom = Math.min(clip.height, Math.round(box.y - clip.y + box.height))
    if (right <= left || bottom <= top) throw new Error(`${captureId}: 영역 ${key} 대상이 캡처 범위 밖에 있습니다`)
    return { x: left, y: top, width: right - left, height: bottom - top }
  }

  /** PNG → WebP. 캡처 대상 화면을 건드리지 않도록 같은 컨텍스트의 빈 탭에서 변환한다. */
  private async toWebp(png: Buffer): Promise<Buffer> {
    const converter = await this.page.context().newPage()
    try {
      const base64 = await converter.evaluate(async ({ source, quality }) => {
        const blob = await (await fetch('data:image/png;base64,' + source)).blob()
        const bitmap = await createImageBitmap(blob)
        const canvas = new OffscreenCanvas(bitmap.width, bitmap.height)
        const context = canvas.getContext('2d')
        if (context === null) throw new Error('canvas 2d 컨텍스트 없음')
        context.drawImage(bitmap, 0, 0)
        const encoded = await canvas.convertToBlob({ type: 'image/webp', quality })
        if (encoded.type !== 'image/webp') throw new Error('WebP 인코딩 미지원: ' + encoded.type)
        const bytes = new Uint8Array(await encoded.arrayBuffer())
        let binary = ''
        for (const byte of bytes) binary += String.fromCharCode(byte)
        return btoa(binary)
      }, { source: png.toString('base64'), quality: WEBP_QUALITY })
      return Buffer.from(base64, 'base64')
    } finally {
      await converter.close()
    }
  }

  private readIndex(): ManualCaptureIndex {
    if (!existsSync(this.indexFile)) return {}
    return JSON.parse(readFileSync(this.indexFile, 'utf-8')) as ManualCaptureIndex
  }
}
