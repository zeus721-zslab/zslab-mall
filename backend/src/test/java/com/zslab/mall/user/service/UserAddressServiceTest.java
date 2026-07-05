package com.zslab.mall.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.user.controller.request.CreateAddressRequest;
import com.zslab.mall.user.controller.request.UpdateAddressRequest;
import com.zslab.mall.user.controller.response.AddressResponse;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.entity.UserAddress;
import com.zslab.mall.user.exception.AddressNotFoundException;
import com.zslab.mall.user.repository.UserAddressRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link UserAddressService} 오케스트레이션 검증(Mockito). 목록·생성(첫 주소 자동 기본·demote-then-set)·수정·삭제(soft)·
 * 기본설정과 소유권 스코프(findByIdAndUserId empty→404) 분기를 커버한다. 실 저장·@SQLRestriction은 통합 테스트 소관.
 */
@ExtendWith(MockitoExtension.class)
class UserAddressServiceTest {

    private static final long USER_ID = 7100L;
    private static final long ADDRESS_ID = 8100L;

    @Mock
    private UserAddressRepository userAddressRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserAddressService userAddressService;

    private User user() {
        return User.create("addr@zslab.test", "주소소유자", "010-1234-5678");
    }

    private CreateAddressRequest createRequest(boolean isDefault) {
        return new CreateAddressRequest(
                isDefault, "집", "홍길동", "010-1111-2222", "12345", "서울시 강남구 테헤란로 1", null, "101호");
    }

    private UserAddress addressWith(boolean isDefault) {
        return UserAddress.create(
                user(), isDefault, "집", "홍길동", "010-1111-2222", "12345", "서울시 강남구 테헤란로 1", null, "101호");
    }

    @Test
    @DisplayName("목록: findByUserId 결과를 AddressResponse로 매핑")
    void list_returnsResponses() {
        when(userAddressRepository.findByUserId(USER_ID)).thenReturn(List.of(addressWith(true)));

        List<AddressResponse> result = userAddressService.list(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isDefault()).isTrue();
        assertThat(result.get(0).recipientName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("생성 첫 주소: count=0 → 요청 isDefault=false여도 기본 강제")
    void create_firstAddress_forcedDefault() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(userAddressRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(userAddressRepository.findByUserIdAndIsDefaultTrue(USER_ID)).thenReturn(Optional.empty());
        when(userAddressRepository.save(any(UserAddress.class))).thenAnswer(inv -> inv.getArgument(0));

        AddressResponse response = userAddressService.create(USER_ID, createRequest(false));

        assertThat(response.isDefault()).isTrue();
    }

    @Test
    @DisplayName("생성 기본 요청: 기존 기본 있으면 강등 후 신규를 기본으로")
    void create_requestDefault_demotesExisting() {
        UserAddress existingDefault = addressWith(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(userAddressRepository.countByUserId(USER_ID)).thenReturn(2L);
        when(userAddressRepository.findByUserIdAndIsDefaultTrue(USER_ID)).thenReturn(Optional.of(existingDefault));
        when(userAddressRepository.save(any(UserAddress.class))).thenAnswer(inv -> inv.getArgument(0));

        AddressResponse response = userAddressService.create(USER_ID, createRequest(true));

        assertThat(existingDefault.isDefault()).isFalse(); // 기존 기본 강등
        assertThat(response.isDefault()).isTrue();
    }

    @Test
    @DisplayName("생성 비기본: count>0·요청 false → 강등 조회 없음·비기본 생성")
    void create_nonDefault_noDemote() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user()));
        when(userAddressRepository.countByUserId(USER_ID)).thenReturn(1L);
        when(userAddressRepository.save(any(UserAddress.class))).thenAnswer(inv -> inv.getArgument(0));

        AddressResponse response = userAddressService.create(USER_ID, createRequest(false));

        assertThat(response.isDefault()).isFalse();
        verify(userAddressRepository, never()).findByUserIdAndIsDefaultTrue(any());
    }

    @Test
    @DisplayName("생성 시 User 부재: findById empty → IllegalStateException·저장 없음")
    void create_userNotFound_throws() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userAddressService.create(USER_ID, createRequest(false)))
                .isInstanceOf(IllegalStateException.class);

        verify(userAddressRepository, never()).save(any());
    }

    @Test
    @DisplayName("수정: 소유 주소 상세 교체·save·응답 반영")
    void update_success() {
        UserAddress address = addressWith(false);
        when(userAddressRepository.findByIdAndUserId(ADDRESS_ID, USER_ID)).thenReturn(Optional.of(address));

        AddressResponse response = userAddressService.update(USER_ID, ADDRESS_ID,
                new UpdateAddressRequest("회사", "김철수", "010-9999-8888", "54321", "부산시 해운대구 1", "지번주소", "202호"));

        assertThat(address.getRecipientName()).isEqualTo("김철수");
        assertThat(address.getZonecode()).isEqualTo("54321");
        assertThat(response.recipientName()).isEqualTo("김철수");
        verify(userAddressRepository).save(address);
    }

    @Test
    @DisplayName("수정 미소유/미존재: findByIdAndUserId empty → AddressNotFoundException·저장 없음")
    void update_notOwned_throws() {
        when(userAddressRepository.findByIdAndUserId(ADDRESS_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userAddressService.update(USER_ID, ADDRESS_ID,
                new UpdateAddressRequest("회사", "김철수", "010-9999-8888", "54321", "부산시 1", null, null)))
                .isInstanceOf(AddressNotFoundException.class);

        verify(userAddressRepository, never()).save(any());
    }

    @Test
    @DisplayName("삭제: 소유 주소 soft delete(deletedAt 마킹)·save")
    void delete_success() {
        UserAddress address = addressWith(false);
        when(userAddressRepository.findByIdAndUserId(ADDRESS_ID, USER_ID)).thenReturn(Optional.of(address));

        userAddressService.delete(USER_ID, ADDRESS_ID);

        assertThat(address.getDeletedAt()).isNotNull();
        verify(userAddressRepository).save(address);
    }

    @Test
    @DisplayName("삭제 미소유/미존재: empty → AddressNotFoundException·저장 없음")
    void delete_notOwned_throws() {
        when(userAddressRepository.findByIdAndUserId(ADDRESS_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userAddressService.delete(USER_ID, ADDRESS_ID))
                .isInstanceOf(AddressNotFoundException.class);

        verify(userAddressRepository, never()).save(any());
    }

    @Test
    @DisplayName("기본설정: 기존 기본 강등 후 대상 승격(demote-then-set)")
    void setDefault_switchesDefault() {
        UserAddress target = addressWith(false);
        UserAddress existingDefault = addressWith(true);
        when(userAddressRepository.findByIdAndUserId(ADDRESS_ID, USER_ID)).thenReturn(Optional.of(target));
        when(userAddressRepository.findByUserIdAndIsDefaultTrue(USER_ID)).thenReturn(Optional.of(existingDefault));

        userAddressService.setDefault(USER_ID, ADDRESS_ID);

        assertThat(existingDefault.isDefault()).isFalse();
        assertThat(target.isDefault()).isTrue();
        // UserAddress.equals는 인스턴스 id를 구분하지 않으므로(추상 부모 상속) 인스턴스 동일성 same()으로 대상 save를 검증한다.
        verify(userAddressRepository).save(same(target));
    }

    @Test
    @DisplayName("기본설정 미소유/미존재: empty → AddressNotFoundException·강등 조회 없음")
    void setDefault_notOwned_throws() {
        when(userAddressRepository.findByIdAndUserId(ADDRESS_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userAddressService.setDefault(USER_ID, ADDRESS_ID))
                .isInstanceOf(AddressNotFoundException.class);

        verify(userAddressRepository, never()).findByUserIdAndIsDefaultTrue(any());
    }
}
