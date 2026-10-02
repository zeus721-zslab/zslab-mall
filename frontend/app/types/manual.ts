/**
 * 관리자·셀러 웹 매뉴얼(C8) 콘텐츠 모델. 역할별 매뉴얼 = 흐름 섹션[] → 단계[]. 본문은 각 레이어의 매뉴얼 콘텐츠 파일이,
 * 캡처 이미지·영역 좌표는 캡처 파이프라인(walkthrough/manual)이 public/manual/{역할}/captures.json으로 만든다.
 */

export type ManualRole = 'admin' | 'seller'

/** 캡처 위 번호 표시 1개. region은 captures.json의 영역 키(캡처 파이프라인이 data-testid boundingBox로 기록). */
export interface ManualCallout {
  number: number
  region: string
  label: string
  description: string
}

/** 되돌릴 수 없는 동작 경고 블록. */
export interface ManualWarning {
  title: string
  body: string
}

/** 규칙·제약 메모(입력 제한·상한·상태 조건 등). */
export interface ManualRule {
  title: string
  items: string[]
}

export interface ManualStep {
  id: string
  title: string
  paragraphs: string[]
  /** captures.json 키. 캡처가 없는 단계는 null. */
  captureId: string | null
  /** 캡처 대체 텍스트(스크린리더). */
  captureAlt: string
  callouts: ManualCallout[]
  warnings: ManualWarning[]
  rules: ManualRule[]
}

/** 흐름 섹션. steps가 비어 있으면 목차에만 "준비 중"으로 표시하고 본문은 그리지 않는다. */
export interface ManualSection {
  id: string
  title: string
  summary: string
  steps: ManualStep[]
}

export interface ManualDocument {
  role: ManualRole
  title: string
  intro: string
  sections: ManualSection[]
}

/** 캡처 이미지 기준 픽셀 좌표(1440×900 뷰포트에서 사이드바를 뺀 본문 영역 캡처 · deviceScaleFactor 1). */
export interface ManualRegion {
  x: number
  y: number
  width: number
  height: number
}

export interface ManualCaptureMeta {
  file: string
  width: number
  height: number
  bytes: number
  hash: string
  regions: Record<string, ManualRegion>
}

export type ManualCaptureIndex = Record<string, ManualCaptureMeta>
