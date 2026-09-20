import { test, expect, type Page } from '@playwright/test'
import { loginAs } from './helpers/login'
import { SUSPENDED_PROBLEM, mockSellerMe, pagedResponse, pickOption } from './helpers/seller-mock'

/**
 * 셀러 상품 등록·수정 폼(Track 90-C-4) E2E. 로그인은 loginAs(SELLER), 상품 API(목록·상세·POST·PUT 3종·업로드)·카테고리는 page.route mock.
 * ① 등록: 옵션 2그룹(색상 2×사이즈 1=2조합 + 제외 1)·variant 3행 중 2 저장·이미지 1장 업로드 → POST body → PUT images → 승인 안내 → 목록에 PENDING 노출
 * ② 수정: 상품명·가격 변경 → PUT 기본정보만(이미지·variants 미호출) → 재조회 반영 / 옵션 그룹 편집 UI 잠금 / 기존 variant 재고 입력 없음 / HIDDEN 토글 → PUT variants
 * ③ 403 SUSPENDED: PUT 기본정보 403 → danger 토스트 + 배너 + 저장 중단(후속 PUT 미호출·화면 유지).
 * ④ 일부 조합만 존재하는 상품에서 신규 조합 추가 저장(검토 반영) ⑤ partial=1 진입 후 저장 성공 → 쿼리 제거·경고 소거(검토 반영 ⑥).
 */
const PRODUCT_ID = 'prd_E2E0000000000000000000001'
const NEW_PRODUCT_ID = 'prd_E2E0000000000000000000099'
const CATEGORIES = [{ categoryId: 11, displayName: '주방', sortOrder: 0 }, { categoryId: 12, displayName: '생활', sortOrder: 1 }]
const UPLOADED_URL = '/api/v1/files/products/2026/09/E2EULID00000000000000000001.png'

const DETAIL = {
  productPublicId: PRODUCT_ID, name: 'E2E 반찬통', description: '설명', categoryId: 11, categoryName: '주방', status: 'SALE', basePrice: 32000,
  thumbnailUrl: '/api/v1/files/products/e2e/a.jpg', soldoutManual: false, createdAt: '2026-09-17T17:29:23+09:00', updatedAt: '2026-09-17T17:29:23+09:00',
  images: [{ imageId: 501, imageUrl: '/api/v1/files/products/e2e/a.jpg', imageType: 'GALLERY', displayOrder: 0, main: true }],
  optionGroups: [{ optionGroupId: 1, name: '색상', displayOrder: 0, values: [{ optionValueId: 11, value: '블랙', displayOrder: 0 }, { optionValueId: 12, value: '화이트', displayOrder: 1 }] }],
  variants: [
    { variantPublicId: 'var_E2E1', variantCode: 'BLK', sellerSku: 'SKU-BLK', additionalPrice: 500, status: 'SALE', soldoutManual: false, displayOrder: 0,
      options: [{ optionGroupId: 1, optionValueId: 11, value: '블랙' }], quantityOnHand: 10, quantityReserved: 2, quantityAvailable: 8 },
    { variantPublicId: 'var_E2E2', variantCode: 'WHT', additionalPrice: 0, status: 'SALE', soldoutManual: false, displayOrder: 1,
      options: [{ optionGroupId: 1, optionValueId: 12, value: '화이트' }], quantityOnHand: 0, quantityReserved: 0, quantityAvailable: 0 },
  ],
}

interface Captured {
  creates: unknown[]
  updates: { url: string; body: unknown }[]
  images: { url: string; body: unknown }[]
  variants: { url: string; body: unknown }[]
  uploads: number
  listQueries: URLSearchParams[]
}

async function mockSellerProductApis(page: Page, options: { updateStatus?: number; partialCombos?: boolean } = {}): Promise<Captured> {
  const captured: Captured = { creates: [], updates: [], images: [], variants: [], uploads: 0, listQueries: [] }
  const listItems: Record<string, unknown>[] = [{ productPublicId: PRODUCT_ID, name: DETAIL.name, categoryId: 11, categoryName: '주방', status: 'SALE', basePrice: 32000, variantCount: 2, createdAt: DETAIL.createdAt, updatedAt: DETAIL.updatedAt }]
  // partialCombos: 옵션값은 블랙·화이트 2개인데 variant는 블랙만 존재 → 수정 폼에 "추가 가능" 신규 행(화이트)이 노출된다.
  let detail: Record<string, unknown> = options.partialCombos ? { ...DETAIL, variants: [DETAIL.variants[0]] } : { ...DETAIL }
  await mockSellerMe(page)
  await page.route((url) => url.pathname.endsWith('/api/v1/categories'), (route) => route.fulfill({ json: CATEGORIES }))
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/files/images'), (route) => {
    captured.uploads += 1
    return route.fulfill({ json: { results: [{ fileName: 'e2e.png', success: true, url: UPLOADED_URL, thumbnailUrl: UPLOADED_URL, width: 10, height: 10, size: 100 }], successCount: 1, failureCount: 0 } })
  })
  await page.route((url) => /\/api\/v1\/seller\/products\/prd_[^/]+\/images$/.test(url.pathname), (route) => {
    captured.images.push({ url: route.request().url(), body: route.request().postDataJSON() })
    return route.fulfill({ json: detail })
  })
  await page.route((url) => /\/api\/v1\/seller\/products\/prd_[^/]+\/variants$/.test(url.pathname), (route) => {
    const body = route.request().postDataJSON() as { variants: { variantPublicId: string | null; variantCode: string; status: string; initialStock: number; options: { optionGroupId: number; value: string }[] }[] }
    captured.variants.push({ url: route.request().url(), body })
    const existing = (detail.variants as typeof DETAIL.variants).map((variant) => ({ ...variant, status: body.variants.find((row) => row.variantPublicId === variant.variantPublicId)?.status ?? variant.status }))
    // 신규 행(variantPublicId null)은 서버가 생성한 것처럼 id를 부여해 상세에 반영한다(BE PUT variants 계약).
    const created = body.variants.filter((row) => row.variantPublicId === null).map((row, index) => ({
      variantPublicId: `var_E2E_NEW${index}`, variantCode: row.variantCode, additionalPrice: 0, status: row.status, soldoutManual: false, displayOrder: existing.length + index,
      options: row.options.map((option) => ({ optionGroupId: option.optionGroupId, optionValueId: option.value === '화이트' ? 12 : 11, value: option.value })),
      quantityOnHand: row.initialStock, quantityReserved: 0, quantityAvailable: row.initialStock,
    }))
    detail = { ...detail, variants: [...existing, ...created] }
    return route.fulfill({ json: detail })
  })
  await page.route((url) => /\/api\/v1\/seller\/products\/prd_[^/]+$/.test(url.pathname), (route) => {
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON() as { name: string; basePrice: number }
      captured.updates.push({ url: route.request().url(), body })
      if (options.updateStatus === 403) return route.fulfill({ status: 403, json: SUSPENDED_PROBLEM })
      detail = { ...detail, name: body.name, basePrice: body.basePrice }
      return route.fulfill({ json: detail })
    }
    return route.fulfill({ json: detail })
  })
  await page.route((url) => url.pathname.endsWith('/api/v1/seller/products'), (route) => {
    if (route.request().method() === 'POST') {
      const body = route.request().postDataJSON() as { name: string; basePrice: number; categoryId: number; variants: unknown[] }
      captured.creates.push(body)
      listItems.unshift({ productPublicId: NEW_PRODUCT_ID, name: body.name, categoryId: body.categoryId, categoryName: '주방', status: 'PENDING', basePrice: body.basePrice, variantCount: body.variants.length, createdAt: '2026-09-20T10:00:00+09:00', updatedAt: '2026-09-20T10:00:00+09:00' })
      return route.fulfill({ status: 201, json: { productPublicId: NEW_PRODUCT_ID, variantPublicIds: body.variants.map((_, index) => `var_NEW${index}`) } })
    }
    captured.listQueries.push(new URL(route.request().url()).searchParams)
    return route.fulfill({ json: pagedResponse(listItems) })
  })
  return captured
}

async function fill(page: Page, testId: string, value: string, scope: ReturnType<Page['locator']> | Page = page): Promise<void> {
  await scope.getByTestId(testId).locator('input').fill(value)
}

test.describe('셀러 상품 등록·수정 폼(90-C-4)', () => {
  test('① 등록: 목록 → 등록 버튼 → 옵션 2그룹·조합 3행(1 제외)·이미지 1장 → POST body(옵션·optionKeys·초기재고) → PUT images → 승인 안내 → 목록에 PENDING', async ({ page }) => {
    const captured = await mockSellerProductApis(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products')
    await page.getByTestId('product-new').click()
    await page.waitForURL(/\/seller\/products\/new\?back=/)
    await expect(page.getByTestId('seller-product-form')).toBeVisible()
    await expect(page.getByTestId('section-basic')).not.toContainText('공급가')
    await expect(page.getByTestId('field-supply-price')).toHaveCount(0)
    await expect(page.getByTestId('field-sale-start')).toHaveCount(0)

    // 기본정보
    await pickOption(page, 'field-category', '주방')
    await fill(page, 'field-name', 'E2E 새 주전자')
    await fill(page, 'field-base-price', '45000')

    // 이미지 1장 업로드(mock URL만 폼에 들어간다)
    await page.getByTestId('file-input-GALLERY').setInputFiles({ name: 'e2e.png', mimeType: 'image/png', buffer: Buffer.from('89504e470d0a1a0a', 'hex') })
    await expect(page.getByTestId('image-section-GALLERY').getByTestId('image-card')).toHaveCount(1)
    expect(captured.uploads).toBe(1)

    // 옵션 상품: 색상{블랙·실버} × 용량{1.7L} = 2조합 → 그룹 하나 더 추가 후 값 입력
    await page.getByTestId('option-mode-options').click()
    const groups = page.getByTestId('option-group')
    await expect(groups).toHaveCount(1)
    await groups.nth(0).getByTestId('option-group-name').locator('input').fill('색상')
    const colorValues = groups.nth(0).getByTestId('option-group-values').locator('input')
    await colorValues.fill('블랙'); await colorValues.press('Enter')
    await colorValues.fill('실버'); await colorValues.press('Enter')
    await colorValues.fill('골드'); await colorValues.press('Enter')
    await page.getByTestId('option-group-add').click()
    await expect(groups).toHaveCount(2)
    await groups.nth(1).getByTestId('option-group-name').locator('input').fill('용량')
    const sizeValues = groups.nth(1).getByTestId('option-group-values').locator('input')
    await sizeValues.fill('1.7L'); await sizeValues.press('Enter')
    await expect(page.getByTestId('variant-row')).toHaveCount(3)
    await expect(page.getByTestId('variant-count')).toHaveText('3개')
    // 초기 재고 입력·3번째 조합(골드) 제외
    const rows = page.getByTestId('variant-row')
    await rows.nth(0).getByTestId('variant-initial-stock').locator('input').fill('5')
    await rows.nth(1).getByTestId('variant-initial-stock').locator('input').fill('2')
    await rows.nth(1).getByTestId('variant-additional').locator('input').fill('1000')
    await rows.nth(2).getByTestId('variant-exclude').locator('input').click({ force: true })
    await expect(page.getByTestId('section-options')).toContainText('저장 2개')

    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('관리자 승인 후 판매 화면에 노출됩니다')
    await page.waitForURL(/\/seller\/products$/)
    expect(captured.creates).toHaveLength(1)
    const created = captured.creates[0] as { name: string; basePrice: number; categoryId: number; thumbnailUrl: string; optionGroups: { name: string; values: { value: string }[] }[]; variants: { variantCode: string; initialStock: number; additionalPrice: number; optionKeys: string[] }[] }
    expect(created.name).toBe('E2E 새 주전자')
    expect(created.basePrice).toBe(45000)
    expect(created.categoryId).toBe(11)
    expect(created.thumbnailUrl).toBe(UPLOADED_URL)
    expect(created).not.toHaveProperty('supplyPrice')
    expect(created.optionGroups.map((group) => [group.name, group.values.map((value) => value.value)])).toEqual([['색상', ['블랙', '실버', '골드']], ['용량', ['1.7L']]])
    expect(created.variants.map((variant) => [variant.variantCode, variant.initialStock, variant.additionalPrice, variant.optionKeys.length])).toEqual([['블랙-1.7L', 5, 0, 2], ['실버-1.7L', 2, 1000, 2]])
    expect(captured.images).toHaveLength(1)
    expect(captured.images[0]?.url).toContain(`/api/v1/seller/products/${NEW_PRODUCT_ID}/images`)
    expect(captured.images[0]?.body).toEqual({ images: [{ imageId: null, imageUrl: UPLOADED_URL, imageType: 'GALLERY', main: true }] })
    // 목록 첫 행 = 방금 등록(PENDING)
    await expect(page.getByTestId('row-product-name').first()).toHaveText('E2E 새 주전자')
    await expect(page.getByTestId('status-chip').first()).toHaveText('승인대기')
  })

  test('② 수정: 옵션 구조 잠금·기존 재고 입력 없음 → 상품명·가격 변경 → PUT 기본정보만 → 재조회 반영 → 사용 토글(HIDDEN) → PUT variants만', async ({ page }) => {
    const captured = await mockSellerProductApis(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/seller/products')
    await page.getByTestId('row-edit').first().click()
    await page.waitForURL(new RegExp(`/seller/products/${PRODUCT_ID}\\?back=`))
    await expect(page.getByTestId('seller-product-form')).toBeVisible()
    await expect(page.getByTestId('status-chip')).toHaveText('판매중')
    await expect(page.getByTestId('status-menu')).toHaveCount(0)

    // 옵션 그룹 편집 UI 잠금
    await expect(page.getByTestId('option-locked-notice')).toBeVisible()
    await expect(page.getByTestId('option-group-name-locked')).toHaveCount(1)
    await expect(page.getByTestId('option-group-name')).toHaveCount(0)
    await expect(page.getByTestId('option-group-values')).toHaveCount(0)
    await expect(page.getByTestId('option-group-add')).toHaveCount(0)
    await expect(page.getByTestId('option-mode-options')).toHaveCount(0)
    // 기존 variant 2행: 재고 입력 없음·읽기 전용 + 재고 링크
    const existingRows = page.locator('[data-testid="variant-row"][data-kind="existing"]')
    await expect(existingRows).toHaveCount(2)
    await expect(existingRows.first().getByTestId('variant-initial-stock')).toHaveCount(0)
    await expect(existingRows.first().getByTestId('variant-stock-readonly')).toContainText('가용 8')
    await expect(existingRows.first().getByTestId('variant-stock-link')).toHaveAttribute('href', `/seller/products/inventory?productPublicId=${PRODUCT_ID}`)
    await expect(page.locator('[data-testid="variant-row"][data-kind="new"]')).toHaveCount(0) // 조합 전부 존재 → 추가 가능 행 없음

    // 상품명·가격 변경 → 저장 → PUT 기본정보만 호출 → 재조회 반영
    await fill(page, 'field-name', 'E2E 반찬통 v2')
    await fill(page, 'field-base-price', '33000')
    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('상품을 저장했습니다.')
    await expect.poll(() => captured.updates.length).toBe(1)
    expect(captured.updates[0]?.body).toEqual({ categoryId: 11, name: 'E2E 반찬통 v2', description: '설명', basePrice: 33000 })
    expect(captured.images).toHaveLength(0)
    expect(captured.variants).toHaveLength(0)
    await expect(page.getByTestId('field-name').locator('input')).toHaveValue('E2E 반찬통 v2')
    await expect(page.locator('.slr-page-header')).toContainText('E2E 반찬통 v2')

    // 사용 토글 off(HIDDEN) → 저장 → PUT variants만(기본정보 미호출)
    await page.locator('[data-testid="variant-row"][data-kind="existing"]').nth(1).getByTestId('variant-enabled').locator('input').click({ force: true })
    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('seller-toaster').getByText('상품을 저장했습니다.')).toHaveCount(2)
    await expect.poll(() => captured.variants.length).toBe(1)
    const variantsBody = captured.variants[0]?.body as { variants: { variantPublicId: string | null; status: string; initialStock: number; options: unknown[] }[] }
    expect(variantsBody.variants.map((row) => [row.variantPublicId, row.status, row.initialStock, row.options.length])).toEqual([['var_E2E1', 'SALE', 0, 0], ['var_E2E2', 'HIDDEN', 0, 0]])
    expect(captured.updates).toHaveLength(1)
    await expect(page.locator('[data-testid="variant-row"][data-kind="existing"]').nth(1)).toHaveClass(/slr-variant-row--hidden/)
  })

  test('③ 403 SELLER_SUSPENDED: PUT 기본정보 403 → danger 토스트 + 정지 배너 + 저장 중단(후속 PUT 미호출·화면 유지·실패 alert)', async ({ page }) => {
    const captured = await mockSellerProductApis(page, { updateStatus: 403 })
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/seller/products/${PRODUCT_ID}`)
    await expect(page.getByTestId('seller-product-form')).toBeVisible()
    await expect(page.getByTestId('seller-suspended-notice')).toHaveCount(0)

    await fill(page, 'field-name', 'E2E 정지 테스트')
    await page.locator('[data-testid="variant-row"][data-kind="existing"]').nth(0).getByTestId('variant-enabled').locator('input').click({ force: true })
    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('정지 상태의 셀러는 변경 작업을 할 수 없습니다')
    await expect(page.getByTestId('seller-suspended-notice')).toBeVisible()
    await expect(page.getByTestId('save-failed-alert')).toContainText('기본정보 저장')
    expect(captured.updates).toHaveLength(1)
    expect(captured.variants).toHaveLength(0)
    await expect(page).toHaveURL(new RegExp(`/seller/products/${PRODUCT_ID}`))
    await expect(page.getByTestId('field-name').locator('input')).toHaveValue('E2E 정지 테스트')
  })

  test('④ 일부 조합만 존재하는 상품: 신규 조합(화이트) "추가" 체크 + 초기재고 → PUT variants(기존 메타 + 신규 options·initialStock) → 재조회 후 기존 행 2개', async ({ page }) => {
    const captured = await mockSellerProductApis(page, { partialCombos: true })
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/seller/products/${PRODUCT_ID}`)
    await expect(page.getByTestId('seller-product-form')).toBeVisible()
    await expect(page.locator('[data-testid="variant-row"][data-kind="existing"]')).toHaveCount(1)
    const addable = page.locator('[data-testid="variant-row"][data-kind="new"]')
    await expect(addable).toHaveCount(1)
    await expect(addable).toContainText('화이트')
    // 추가 체크 전에는 저장 대상이 아니다(입력 비활성)
    await expect(addable.getByTestId('variant-initial-stock').locator('input')).toBeDisabled()
    await addable.getByTestId('variant-add').locator('input').click({ force: true })
    await expect(addable.getByTestId('variant-code').locator('input')).toHaveValue('화이트')
    await addable.getByTestId('variant-initial-stock').locator('input').fill('7')
    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('상품을 저장했습니다.')
    await expect.poll(() => captured.variants.length).toBe(1)
    const body = captured.variants[0]?.body as { variants: { variantPublicId: string | null; variantCode: string; initialStock: number; options: { optionGroupId: number; value: string }[] }[] }
    expect(body.variants.map((row) => [row.variantPublicId, row.variantCode, row.initialStock, row.options])).toEqual([
      ['var_E2E1', 'BLK', 0, []],
      [null, '화이트', 7, [{ optionGroupId: 1, value: '화이트' }]],
    ])
    expect(captured.updates).toHaveLength(0)
    // 재조회 반영: 신규 행이 기존 행이 되고 추가 가능 행은 없다
    await expect(page.locator('[data-testid="variant-row"][data-kind="existing"]')).toHaveCount(2)
    await expect(page.locator('[data-testid="variant-row"][data-kind="new"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="variant-row"][data-kind="existing"]').nth(1).getByTestId('variant-stock-readonly')).toContainText('가용 7')
  })

  test('⑤ partial=1 진입 → 경고 표시 → 저장 성공 → partial 쿼리 제거·경고 사라짐', async ({ page }) => {
    await mockSellerProductApis(page)
    await loginAs(page, 'SELLER')
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto(`/seller/products/${PRODUCT_ID}?back=%2Fseller%2Fproducts&partial=1`)
    await expect(page.getByTestId('partial-alert')).toBeVisible()
    await fill(page, 'field-name', 'E2E partial 후 저장')
    await page.getByTestId('form-save').click()
    await expect(page.getByTestId('seller-toaster')).toContainText('상품을 저장했습니다.')
    await expect(page).not.toHaveURL(/partial=1/)
    await expect(page).toHaveURL(/back=/)
    await expect(page.getByTestId('partial-alert')).toHaveCount(0)
    await expect(page.getByTestId('field-name').locator('input')).toHaveValue('E2E partial 후 저장')
  })
})
