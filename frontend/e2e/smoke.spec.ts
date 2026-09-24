import { test, expect } from '@playwright/test'

/**
 * Browser/SSR Smoke(FE-15 STEP3) — E2E 아님.
 * 목록(/products) goto → 첫 ProductCard의 상세 링크 href 추출(publicId 랜덤이라 하드코딩 금지) → 상세 goto(client click 아닌 새 SSR 로드)
 * → 상품명·가격이 SSR 렌더됐는지 assert. 인증·click→API·쓰기 흐름은 범위 밖(별도 인증 트랙 이월).
 *
 * 전제: 데모 시드 상품 2건(catalog.demo-seed.enabled=true·local). 시드 미기동이면 목록이 EmptyState라 카드가 없어
 * 아래 첫 단언이 skip이 아니라 명확히 실패한다(명시 메시지 포함).
 */
test('상품 목록에서 첫 상품 상세로 이동하면 상품명·가격이 SSR 렌더된다', async ({ page }) => {
  await page.goto('/products')

  // 목록 그리드의 상품 카드(상세 링크). 헤더·배너 링크는 대상이 아니다.
  const firstCard = page.getByTestId('product-card').first()
  await expect(
    firstCard,
    '상품 카드가 없습니다 — 데모 시드 미기동으로 추정(catalog.demo-seed.enabled 확인).',
  ).toBeVisible()

  // 카드 링크는 상품명에 걸린다(Track 105-4g-3 — 카드 전체는 after 오버레이로 누른다).
  const href = await firstCard.getByRole('link').getAttribute('href')
  expect(href, '첫 상품 카드의 href를 추출하지 못했습니다.').toBeTruthy()
  // 카드에 렌더된 상품명. 상세 h1과 대조해 목록→상세 정합을 확인한다.
  const listName = (await firstCard.getByTestId('product-card-name').innerText()).trim()
  expect(listName, '첫 상품 카드의 상품명이 비어 있습니다.').not.toBe('')

  // 새 goto = 상세 페이지 SSR 재렌더(SPA client 이동이 아니라 서버 렌더 경로 검증).
  await page.goto(href!)

  // 상세 SSR 요소 1: 상품명 h1이 목록 카드명과 일치.
  await expect(page.getByTestId('product-detail-name')).toHaveText(listName)
  // 상세 SSR 요소 2: 가격 표기(formattedPrice = toLocaleString('ko-KR') + '원', 예 "39,900원").
  await expect(page.getByTestId('product-detail-price')).toHaveText(/[0-9][0-9,]*원/)
})
