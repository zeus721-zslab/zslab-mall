import { describe, it, expect } from 'vitest'
import { isUnchanged, buildCreateAddressRequest, type CheckoutAddressForm } from '~/lib/utils/address-form'
import type { Address } from '~/types/address'

const baseAddress: Address = {
  id: 1,
  isDefault: true,
  addressLabel: '집',
  recipientName: '홍길동',
  recipientPhone: '010-1234-5678',
  zonecode: '06236',
  addressRoad: '서울시 강남구 테헤란로 1',
  addressJibun: '역삼동 1',
  addressDetail: '101호',
}

const matchingForm: CheckoutAddressForm = {
  recipientName: '홍길동',
  recipientPhone: '010-1234-5678',
  zonecode: '06236',
  addressRoad: '서울시 강남구 테헤란로 1',
  addressJibun: '역삼동 1',
  addressDetail: '101호',
}

describe('isUnchanged', () => {
  it('선택된 주소가 없으면(null) 항상 변경으로 간주해 false를 반환한다', () => {
    expect(isUnchanged(null, matchingForm)).toBe(false)
  })

  it('공유 6필드가 완전히 같으면 true를 반환한다', () => {
    expect(isUnchanged(baseAddress, matchingForm)).toBe(true)
  })

  it('앞뒤 공백만 다르면 동일한 것으로 간주해 true를 반환한다', () => {
    const form: CheckoutAddressForm = { ...matchingForm, recipientName: '  홍길동  ', zonecode: ' 06236 ' }
    expect(isUnchanged(baseAddress, form)).toBe(true)
  })

  it('한 필드라도 값이 다르면 false를 반환한다', () => {
    const form: CheckoutAddressForm = { ...matchingForm, addressDetail: '202호' }
    expect(isUnchanged(baseAddress, form)).toBe(false)
  })

  it('주소의 선택 필드가 undefined이고 폼이 빈 문자열이면 동일 취급한다', () => {
    const address: Address = { ...baseAddress, addressJibun: undefined, addressDetail: undefined }
    const form: CheckoutAddressForm = { ...matchingForm, addressJibun: '', addressDetail: '' }
    expect(isUnchanged(address, form)).toBe(true)
  })
})

describe('buildCreateAddressRequest', () => {
  it('빈(또는 공백뿐인) 선택 필드는 undefined로 변환한다', () => {
    const request = buildCreateAddressRequest({ ...matchingForm, addressJibun: '', addressDetail: '   ' })
    expect(request.addressJibun).toBeUndefined()
    expect(request.addressDetail).toBeUndefined()
  })

  it('isDefault는 false이고 addressLabel·deliveryMemo는 포함하지 않는다', () => {
    const request = buildCreateAddressRequest(matchingForm)
    expect(request.isDefault).toBe(false)
    expect('addressLabel' in request).toBe(false)
    expect('deliveryMemo' in request).toBe(false)
  })

  it('필수·선택 필드 모두 trim을 적용한다', () => {
    const request = buildCreateAddressRequest({
      ...matchingForm,
      recipientName: '  홍길동  ',
      zonecode: ' 06236 ',
      addressJibun: '  역삼동 1  ',
    })
    expect(request.recipientName).toBe('홍길동')
    expect(request.zonecode).toBe('06236')
    expect(request.addressJibun).toBe('역삼동 1')
  })
})
