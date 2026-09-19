import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'

/**
 * 관리자 상품 등록·수정(FE-26) E2E. 공용 헬퍼 loginAs(ADMIN_E2E_* 주입·미주입 시 skip) + 상품/업로드 API page.route mock(로컬 DB 무변경·결정적).
 * ① 등록(기본·이미지 업로드 mock·옵션 조합) → 호출 순서·목록 복귀(query 보존) ② 수정 로드·변경·저장 ③ 등록 2단계 실패 → 수정 화면 전환·안내
 * ④ 미저장 이탈 경고 ⑤ 이미지 정렬(드래그)·대표 변경 ⑥ 409 조합 안내.
 */
const DETAIL = {
  productPublicId: 'prd_E2E0000000000000000000000E1', name: 'E2E 옵션 상품', description: '설명', categoryId: 1, categoryName: '데모',
  sellerPublicId: 'slr_E2E1', sellerName: 'E2E셀러', status: 'SALE', soldOutManual: false, basePrice: 25000, supplyPrice: 15000,
  images: [
    { imageId: 10, imageUrl: 'https://img.invalid/a.png', imageType: 'GALLERY', displayOrder: 0, main: true },
    { imageId: 11, imageUrl: 'https://img.invalid/b.png', imageType: 'GALLERY', displayOrder: 1, main: false },
  ],
  optionGroups: [
    { optionGroupId: 1, name: '색상', displayOrder: 0, values: [{ optionValueId: 11, value: '블랙', displayOrder: 0 }] },
  ],
  variants: [
    { variantPublicId: 'var_E2E1', variantCode: 'BLK', additionalPrice: 0, status: 'SALE', soldOutManual: false, displayOrder: 0,
      quantityAvailable: 5, quantityOnHand: 5, options: [{ optionGroupId: 1, groupName: '색상', optionValueId: 11, value: '블랙' }] },
  ],
}

interface Captured { calls: { method: string; url: string; body: string }[] }

async function mockApi(page: Page, options: { failImages?: boolean; variantsConflict?: boolean } = {}): Promise<Captured> {
  const captured: Captured = { calls: [] }
  const record = (method: string, url: string, body: string | null) => captured.calls.push({ method, url: new URL(url).pathname, body: body ?? '' })
  await page.route('**/api/v1/admin/sellers', (route) => route.fulfill({ json: [{ sellerPublicId: 'slr_E2E1', companyName: 'E2E셀러', status: 'ACTIVE' }] }))
  await page.route('**/api/v1/categories', (route) => route.fulfill({ json: [{ categoryId: 1, displayName: '데모', sortOrder: 0 }] }))
  await page.route('**/api/v1/admin/files/images', (route) => {
    record('POST', route.request().url(), null)
    const index = captured.calls.filter((c) => c.url.endsWith('/files/images')).length
    route.fulfill({ json: { results: [{ fileName: `f${index}`, success: true, url: `/api/v1/files/products/2026/09/UP${index}.png`,
      thumbnailUrl: `/api/v1/files/products/2026/09/UP${index}_thumb.png`, width: 800, height: 600, size: 1000 }], successCount: 1, failureCount: 0 } })
  })
  await page.route((url) => url.pathname === '/api/v1/admin/products' && url.search !== '', (route) =>
    route.fulfill({ json: { items: [], page: 0, size: 20, totalCount: 0, hasNext: false } }))
  await page.route((url) => url.pathname === '/api/v1/admin/products' && url.search === '', (route) => {
    record(route.request().method(), route.request().url(), route.request().postData())
    route.fulfill({ status: 201, json: { productPublicId: DETAIL.productPublicId, variantPublicIds: ['var_new1', 'var_new2'] } })
  })
  await page.route((url) => url.pathname.startsWith('/api/v1/admin/products/prd_'), (route) => {
    const method = route.request().method()
    const url = new URL(route.request().url()).pathname
    record(method, route.request().url(), route.request().postData())
    if (url.endsWith('/images') && method === 'PUT') {
      if (options.failImages) return route.fulfill({ status: 500, contentType: 'application/problem+json', json: { code: 'INTERNAL_ERROR', detail: 'x' } })
      return route.fulfill({ json: DETAIL })
    }
    if (url.endsWith('/variants') && method === 'PUT') {
      if (options.variantsConflict) {
        return route.fulfill({ status: 409, contentType: 'application/problem+json', json: { code: 'PRODUCT_VARIANT_OPTION_CONFLICT', detail: 'dup' } })
      }
      return route.fulfill({ json: DETAIL })
    }
    if (method === 'GET') return route.fulfill({ json: DETAIL })
    if (method === 'PUT') return route.fulfill({ json: DETAIL })
    return route.fulfill({ json: {} })
  })
  await page.route('**/api/v1/admin/inventories/**', (route) => {
    record('POST', route.request().url(), route.request().postData())
    route.fulfill({ json: { variantPublicId: 'var_E2E1', quantityOnHand: 9, quantityReserved: 0, quantityAvailable: 9 } })
  })
  return captured
}

const PNG_1X1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

test.describe('관리자 상품 등록·수정(FE-26)', () => {
  test('① 등록: 기본정보·이미지 업로드(mock)·옵션 2값 조합 → POST→PUT images→PUT variants 순서·목록 복귀(back query 보존)', async ({ page }) => {
    const captured = await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/new?back=%2Fadmin%2Fproducts%3Fstatus%3DSALE')
    await expect(page.getByTestId('section-basic')).toBeVisible()

    await page.getByTestId('field-seller').click()
    await page.getByRole('option', { name: 'E2E셀러' }).click()
    await page.getByTestId('field-category').click()
    await page.getByRole('option', { name: '데모' }).click()
    await page.getByTestId('field-name').locator('input').fill('E2E 신규 상품')
    await page.getByTestId('field-base-price').locator('input').fill('12000')

    await page.getByTestId('file-input-GALLERY').setInputFiles({ name: 'a.png', mimeType: 'image/png', buffer: PNG_1X1 })
    await expect(page.getByTestId('image-section-GALLERY').getByTestId('image-card')).toHaveCount(1)
    await expect(page.getByTestId('image-section-GALLERY').getByTestId('image-card').first()).toHaveClass(/adm-image-card--main/)
    await page.waitForTimeout(400)
    await page.screenshot({ path: 'playwright-report/fe-26/image-grid-desktop.png', fullPage: false })

    await page.getByTestId('option-mode-options').click()
    await page.getByTestId('option-group-name').locator('input').fill('색상')
    const values = page.getByTestId('option-group-values').locator('input')
    await values.fill('블랙'); await values.press('Enter')
    await values.fill('화이트'); await values.press('Enter')
    await expect(page.getByTestId('variant-count')).toHaveText('2개')
    await page.getByTestId('bulk-stock').locator('input').fill('7')
    await page.getByTestId('bulk-apply').click()
    await expect(page.getByTestId('variant-stock').first().locator('input')).toHaveValue('7')
    // 두 번째 조합은 수동 품절 → 등록 후 PUT variants 필요
    await page.getByTestId('variant-soldout').nth(1).locator('input').click({ force: true })
    await page.screenshot({ path: 'playwright-report/fe-26/options-desktop.png', fullPage: true })

    await page.getByTestId('form-save').click()
    await expect(page).toHaveURL(/^http:\/\/[^/]+\/admin\/products\?status=SALE$/) // back 파라미터 자체가 아닌 실제 경로
    const sequence = captured.calls.filter((c) => c.url.startsWith('/api/v1/admin/products')).map((c) => `${c.method} ${c.url.replace(DETAIL.productPublicId, '{id}')}`)
    expect(sequence).toEqual(['POST /api/v1/admin/products', 'PUT /api/v1/admin/products/{id}/images', 'PUT /api/v1/admin/products/{id}/variants'])
    const created = JSON.parse(captured.calls.find((c) => c.method === 'POST' && c.url === '/api/v1/admin/products')?.body ?? '{}')
    expect(created.sellerPublicId).toBe('slr_E2E1')
    expect(created.thumbnailUrl).toBe('/api/v1/files/products/2026/09/UP1_thumb.png')
    expect(created.optionGroups[0].values.map((v: { value: string }) => v.value)).toEqual(['블랙', '화이트'])
    expect(created.variants.map((v: { initialStock: number }) => v.initialStock)).toEqual([7, 7])
    await expect(page.locator('[data-sonner-toast][data-type="success"]')).toContainText('등록했습니다')
  })

  test('② 수정: 상세 로드(셀러 읽기 전용·조합 1행·이미지 2장) → 이름·재고 변경 → PUT basic→images→variants→adjust(delta +4)', async ({ page }) => {
    const captured = await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/products/${DETAIL.productPublicId}?back=%2Fadmin%2Fproducts%3Fpage%3D1`)
    await expect(page.getByTestId('field-seller-readonly').locator('input')).toHaveValue('E2E셀러')
    await expect(page.getByTestId('status-chip')).toHaveText('판매중')
    await expect(page.getByTestId('variant-row')).toHaveCount(1)
    await expect(page.getByTestId('image-section-GALLERY').getByTestId('image-card')).toHaveCount(2)
    await page.waitForTimeout(400)
    await page.screenshot({ path: 'playwright-report/fe-26/edit-desktop.png', fullPage: true })

    await page.getByTestId('field-name').locator('input').fill('E2E 옵션 상품(수정)')
    await page.getByTestId('variant-stock').first().locator('input').fill('9')
    await page.getByTestId('form-save').click()
    await expect(page).toHaveURL(/^http:\/\/[^/]+\/admin\/products\?page=1$/)
    const sequence = captured.calls.filter((c) => c.method !== 'GET').map((c) => `${c.method} ${c.url.replace(DETAIL.productPublicId, '{id}')}`)
    expect(sequence).toEqual([
      'PUT /api/v1/admin/products/{id}', 'PUT /api/v1/admin/products/{id}/images', 'PUT /api/v1/admin/products/{id}/variants',
      'POST /api/v1/admin/inventories/var_E2E1/adjust',
    ])
    expect(JSON.parse(captured.calls.at(-1)?.body ?? '{}')).toMatchObject({ quantityDelta: 4 })
  })

  test('③ 등록 2단계(images) 실패 → danger 토스트·수정 화면으로 전환(partial 안내)·재등록 없음', async ({ page }) => {
    const captured = await mockApi(page, { failImages: true })
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/new')
    await page.getByTestId('field-seller').click()
    await page.getByRole('option', { name: 'E2E셀러' }).click()
    await page.getByTestId('field-category').click()
    await page.getByRole('option', { name: '데모' }).click()
    await page.getByTestId('field-name').locator('input').fill('부분 실패')
    await page.getByTestId('field-base-price').locator('input').fill('100')
    await page.getByTestId('file-input-DETAIL').setInputFiles({ name: 'd.png', mimeType: 'image/png', buffer: PNG_1X1 })
    await expect(page.getByTestId('image-section-DETAIL').getByTestId('image-card')).toHaveCount(1)
    await page.getByTestId('form-save').click()
    await expect(page.locator('[data-sonner-toast][data-type="error"]')).toContainText('이미지 저장 실패')
    await expect(page).toHaveURL(new RegExp(`/admin/products/${DETAIL.productPublicId}\\?.*partial=1`))
    await expect(page.getByTestId('partial-alert')).toBeVisible()
    expect(captured.calls.filter((c) => c.method === 'POST' && c.url === '/api/v1/admin/products')).toHaveLength(1)
  })

  test('④ 미저장 이탈 경고: 변경 후 목록으로 → confirm 취소 시 잔류·확인 시 이동 / 변경 없으면 경고 없음', async ({ page }) => {
    await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/products/${DETAIL.productPublicId}`)
    await expect(page.getByTestId('field-name').locator('input')).toHaveValue('E2E 옵션 상품')
    await page.getByTestId('field-name').locator('input').fill('바뀜')
    let dialogs = 0
    page.once('dialog', (dialog) => { dialogs++; void dialog.dismiss() })
    await page.getByTestId('form-back').click()
    await page.waitForTimeout(300)
    expect(dialogs).toBe(1)
    await expect(page).toHaveURL(new RegExp(`/admin/products/${DETAIL.productPublicId}`))
    page.once('dialog', (dialog) => { dialogs++; void dialog.accept() })
    await page.getByTestId('form-back').click()
    await expect(page).toHaveURL(/\/admin\/products$/)
  })

  test('⑤ 이미지: 대표 변경(별) → 두 번째가 대표·첫 번째 해제 / 드래그 정렬 → 순서 반전 → 저장 images 본문 순서', async ({ page }) => {
    const captured = await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/products/${DETAIL.productPublicId}`)
    const cards = page.getByTestId('image-section-GALLERY').getByTestId('image-card')
    await expect(cards).toHaveCount(2)
    await cards.nth(1).getByTestId('image-main').click()
    await expect(cards.nth(1)).toHaveClass(/adm-image-card--main/)
    await expect(cards.nth(0)).not.toHaveClass(/adm-image-card--main/)

    // 드래그: 두 번째 카드를 첫 번째 위치로(sortablejs·마우스 이벤트)
    const first = await cards.nth(0).boundingBox()
    const second = await cards.nth(1).boundingBox()
    if (!first || !second) throw new Error('카드 bbox 없음')
    await page.mouse.move(second.x + second.width / 2, second.y + second.height / 3)
    await page.mouse.down()
    await page.mouse.move(second.x + second.width / 2 - 10, second.y + second.height / 3, { steps: 4 })
    await page.mouse.move(first.x + 10, first.y + first.height / 3, { steps: 12 })
    await page.mouse.up()
    await expect(cards.nth(0).locator('img')).toHaveAttribute('src', 'https://img.invalid/b.png')

    await page.getByTestId('form-save').click()
    await expect(page).toHaveURL(/\/admin\/products$/)
    const imagesBody = JSON.parse(captured.calls.find((c) => c.url.endsWith('/images') && c.method === 'PUT')?.body ?? '{}')
    expect(imagesBody.images.map((i: { imageId: number; main: boolean }) => [i.imageId, i.main])).toEqual([[11, true], [10, false]])
  })

  test('⑥ 수정 저장 시 variants 409 조합 중복 → warning 토스트 "삭제된 조합은 다시 만들 수 없습니다" + 실패 단계 표시·화면 잔류', async ({ page }) => {
    await mockApi(page, { variantsConflict: true })
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/products/${DETAIL.productPublicId}`)
    await expect(page.getByTestId('variant-row')).toHaveCount(1)
    const values = page.getByTestId('option-group-values').locator('input')
    await values.fill('화이트'); await values.press('Enter')
    await expect(page.getByTestId('variant-row')).toHaveCount(2)
    await page.getByTestId('form-save').click()
    await expect(page.locator('[data-sonner-toast][data-type="warning"]')).toContainText('삭제된 조합은 다시 만들 수 없습니다')
    await expect(page.getByTestId('save-failed-alert')).toContainText('옵션·재고 저장')
    await expect(page).toHaveURL(new RegExp(`/admin/products/${DETAIL.productPublicId}`))
  })

  test('⑧ 입력 컴포넌트 통일: 같은 행 필드 높이 동일(셀러 autocomplete=카테고리 select·판매가=공급가)·textarea도 outlined', async ({ page }) => {
    await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/new')
    await expect(page.getByTestId('field-seller')).toBeVisible()
    const field = (testId: string) => page.getByTestId(testId).locator('.v-field').first()
    const sellerBox = await field('field-seller').boundingBox()
    const categoryBox = await field('field-category').boundingBox()
    const baseBox = await field('field-base-price').boundingBox()
    const supplyBox = await field('field-supply-price').boundingBox()
    expect(sellerBox?.height).toBe(categoryBox?.height)
    expect(baseBox?.height).toBe(supplyBox?.height)
    for (const testId of ['field-seller', 'field-category', 'field-name', 'field-description', 'field-base-price']) {
      await expect(field(testId)).toHaveClass(/v-field--variant-outlined/)
    }
    await expect(page.getByTestId('option-mode-options')).toBeVisible()
    await page.getByTestId('option-mode-options').click()
    await expect(page.getByTestId('option-group-values').locator('.v-field').first()).toHaveClass(/v-field--variant-outlined/)
  })

  test('⑨ 수정: 값 추가 → 신규 조합 "제외" 체크 → 저장 variants 요청에 미포함(기존 행만)·전 행 제외 시 등록 검증 에러', async ({ page }) => {
    const captured = await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto(`/admin/products/${DETAIL.productPublicId}`)
    await expect(page.getByTestId('variant-row')).toHaveCount(1)
    await expect(page.getByTestId('variant-exclude')).toHaveCount(0) // 기존 행에는 제외 체크 없음
    const values = page.getByTestId('option-group-values').locator('input')
    await values.fill('화이트'); await values.press('Enter')
    await expect(page.getByTestId('variant-row')).toHaveCount(2)
    await expect(page.getByTestId('variant-exclude')).toHaveCount(1)
    await page.getByTestId('variant-exclude').locator('input').click({ force: true })
    await expect(page.getByTestId('variant-row').nth(1)).toHaveClass(/adm-variant-row--excluded/)
    await expect(page.getByTestId('variant-row').nth(1).getByTestId('variant-stock').locator('input')).toBeDisabled()
    await page.getByTestId('form-save').click()
    await expect(page).toHaveURL(/^http:\/\/[^/]+\/admin\/products$/)
    const variantsBody = JSON.parse(captured.calls.find((c) => c.url.endsWith('/variants') && c.method === 'PUT')?.body ?? '{}')
    expect(variantsBody.variants.map((v: { variantPublicId: string | null }) => v.variantPublicId)).toEqual(['var_E2E1'])

    // 등록 화면: 단일 상품 행 제외 → 저장 시 검증 에러
    await page.goto('/admin/products/new')
    await page.getByTestId('field-seller').click(); await page.getByRole('option', { name: 'E2E셀러' }).click()
    await page.getByTestId('field-category').click(); await page.getByRole('option', { name: '데모' }).click()
    await page.getByTestId('field-name').locator('input').fill('x'); await page.getByTestId('field-base-price').locator('input').fill('1')
    await page.getByTestId('variant-exclude').locator('input').click({ force: true })
    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('section-options')).toContainText('최소 1개 조합은 포함되어야 합니다')
    await expect(page).toHaveURL(/\/admin\/products\/new/)
  })

  test('⑦ 모바일(390): 등록 화면 렌더·드롭존·조합표 가로 스크롤', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await mockApi(page)
    await loginAs(page, 'ADMIN')
    await page.goto('/admin/products/new')
    await expect(page.getByTestId('dropzone-GALLERY')).toBeVisible()
    await page.waitForTimeout(400)
    await page.screenshot({ path: 'playwright-report/fe-26/new-mobile.png', fullPage: true })
  })
})
