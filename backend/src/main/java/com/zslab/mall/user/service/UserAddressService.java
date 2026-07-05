package com.zslab.mall.user.service;

import com.zslab.mall.user.controller.request.CreateAddressRequest;
import com.zslab.mall.user.controller.request.UpdateAddressRequest;
import com.zslab.mall.user.controller.response.AddressResponse;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.entity.UserAddress;
import com.zslab.mall.user.exception.AddressNotFoundException;
import com.zslab.mall.user.repository.UserAddressRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송지 주소록 Application Service(Track 58 BL-4). 본인 소유 배송지의 목록·생성·수정·삭제(soft)·기본설정을 담당한다.
 * 트랜잭션 경계는 메서드 단위다.
 *
 * <p>소유권은 모든 단건 접근을 {@code findByIdAndUserId}로 스코프해 강제한다(BOLA 차단·타 user 소유는 404 은닉).
 * is_default 단일성은 DB 제약이 아니라 본 서비스의 demote-then-set 로직이 동일 트랜잭션 내에서 보장한다.
 */
@Slf4j
@Service
@Transactional
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;
    private final UserRepository userRepository;

    public UserAddressService(UserAddressRepository userAddressRepository, UserRepository userRepository) {
        this.userAddressRepository = userAddressRepository;
        this.userRepository = userRepository;
    }

    /** 본인 배송지 목록 조회(Track 58 BL-4). */
    @Transactional(readOnly = true)
    public List<AddressResponse> list(Long userId) {
        return userAddressRepository.findByUserId(userId).stream()
                .map(UserAddressService::toResponse)
                .toList();
    }

    /**
     * 배송지 생성(Track 58 BL-4). 첫 주소는 기본으로 강제하고, isDefault=true 요청 시 기존 기본을 강등한 뒤 지정한다.
     *
     * @throws IllegalStateException userId에 해당하는 User가 없는 경우(인증됐으나 데이터 부재·내부 오류·500)
     */
    public AddressResponse create(Long userId, CreateAddressRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("인증된 userId에 해당하는 User가 없습니다: " + userId));

        // 첫 주소는 기본으로 강제(요청값 무관). 그 외엔 요청 isDefault를 따르고, true면 기존 기본을 강등한다.
        boolean makeDefault = userAddressRepository.countByUserId(userId) == 0 || request.isDefault();
        if (makeDefault) {
            demoteCurrentDefault(userId);
        }

        UserAddress address = UserAddress.create(
                user,
                makeDefault,
                request.addressLabel(),
                request.recipientName(),
                request.recipientPhone(),
                request.zonecode(),
                request.addressRoad(),
                request.addressJibun(),
                request.addressDetail());
        UserAddress saved = userAddressRepository.save(address);

        log.info("[UserAddress] 배송지 생성 userId={} addressId={} isDefault={}", userId, saved.getId(), makeDefault);
        return toResponse(saved);
    }

    /**
     * 배송지 수정(Track 58 BL-4). 소유 배송지의 상세를 교체한다. isDefault는 본 경로에서 변경하지 않는다.
     *
     * @throws AddressNotFoundException 배송지가 없거나 요청자 소유가 아닌 경우(404)
     */
    public AddressResponse update(Long userId, Long addressId, UpdateAddressRequest request) {
        UserAddress address = requireOwnedAddress(userId, addressId);
        address.updateDetails(
                request.addressLabel(),
                request.recipientName(),
                request.recipientPhone(),
                request.zonecode(),
                request.addressRoad(),
                request.addressJibun(),
                request.addressDetail());
        userAddressRepository.save(address);

        log.info("[UserAddress] 배송지 수정 userId={} addressId={}", userId, addressId);
        return toResponse(address);
    }

    /**
     * 배송지 삭제(Track 58 BL-4·soft delete). 기본 배송지 삭제 시 다른 주소 자동 승격은 하지 않는다(재설정은 사용자 몫·YAGNI).
     *
     * @throws AddressNotFoundException 배송지가 없거나 요청자 소유가 아닌 경우(404)
     */
    public void delete(Long userId, Long addressId) {
        UserAddress address = requireOwnedAddress(userId, addressId);
        address.markDeleted();
        userAddressRepository.save(address);

        log.info("[UserAddress] 배송지 삭제(soft) userId={} addressId={}", userId, addressId);
    }

    /**
     * 기본 배송지 설정(Track 58 BL-4). 기존 기본을 강등한 뒤 대상을 승격한다(demote-then-set·동일 TX·단일성 보장).
     * 대상이 이미 기본이면 강등·승격이 상쇄되어 멱등이다.
     *
     * @throws AddressNotFoundException 배송지가 없거나 요청자 소유가 아닌 경우(404)
     */
    public void setDefault(Long userId, Long addressId) {
        UserAddress target = requireOwnedAddress(userId, addressId);
        demoteCurrentDefault(userId);
        target.markDefault();
        userAddressRepository.save(target);

        log.info("[UserAddress] 기본 배송지 설정 userId={} addressId={}", userId, addressId);
    }

    /** 기존 기본 배송지를 강등한다(있을 때만). demote-then-set·첫 주소 자동 기본의 공통 선행 단계다. */
    private void demoteCurrentDefault(Long userId) {
        userAddressRepository.findByUserIdAndIsDefaultTrue(userId).ifPresent(current -> {
            current.unmarkDefault();
            userAddressRepository.save(current);
        });
    }

    /**
     * @throws AddressNotFoundException 배송지가 없거나 요청자 소유가 아닌 경우(404·소유권 스코프)
     */
    private UserAddress requireOwnedAddress(Long userId, Long addressId) {
        return userAddressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException("배송지를 찾을 수 없습니다: " + addressId));
    }

    private static AddressResponse toResponse(UserAddress address) {
        return new AddressResponse(
                address.getId(),
                address.isDefault(),
                address.getAddressLabel(),
                address.getRecipientName(),
                address.getRecipientPhone(),
                address.getZonecode(),
                address.getAddressRoad(),
                address.getAddressJibun(),
                address.getAddressDetail());
    }
}
