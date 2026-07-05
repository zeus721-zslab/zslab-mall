package com.zslab.mall.user.controller;

import com.zslab.mall.common.auth.AuthenticatedUserResolver;
import com.zslab.mall.user.controller.request.CreateAddressRequest;
import com.zslab.mall.user.controller.request.UpdateAddressRequest;
import com.zslab.mall.user.controller.response.AddressResponse;
import com.zslab.mall.user.service.UserAddressService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 배송지 주소록 REST 컨트롤러(Track 58 BL-4). 본인 배송지 목록·생성·수정·삭제(soft)·기본설정을 노출한다.
 * {@code /api/v1/users/me/**}는 SecurityConfig에서 authenticated fail-closed(무변경)·소유권은 Service가 강제한다.
 *
 * <p>HTTP 책임만 가진다: 인증 주체 해석·Service 위임·HTTP 변환. 소유권 검증·is_default 단일성은 {@link UserAddressService} 책임.
 */
@RestController
@RequestMapping("/api/v1/users/me/addresses")
public class UserAddressController {

    private final UserAddressService userAddressService;
    private final AuthenticatedUserResolver authenticatedUserResolver;

    public UserAddressController(
            UserAddressService userAddressService, AuthenticatedUserResolver authenticatedUserResolver) {
        this.userAddressService = userAddressService;
        this.authenticatedUserResolver = authenticatedUserResolver;
    }

    /** 본인 배송지 목록(Track 58 BL-4). 성공 200 + List&lt;AddressResponse&gt;. */
    @GetMapping
    public ResponseEntity<List<AddressResponse>> list() {
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.ok(userAddressService.list(userId));
    }

    /** 배송지 생성(Track 58 BL-4). 성공 201 + AddressResponse·검증 실패 400. */
    @PostMapping
    public ResponseEntity<AddressResponse> create(@RequestBody @Valid CreateAddressRequest request) {
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(userAddressService.create(userId, request));
    }

    /** 배송지 수정(Track 58 BL-4). 성공 200 + 수정 후 AddressResponse·미소유/미존재 404·검증 실패 400. */
    @PatchMapping("/{addressId}")
    public ResponseEntity<AddressResponse> update(
            @PathVariable Long addressId, @RequestBody @Valid UpdateAddressRequest request) {
        Long userId = authenticatedUserResolver.requireUserId();
        return ResponseEntity.ok(userAddressService.update(userId, addressId, request));
    }

    /** 배송지 삭제(Track 58 BL-4·soft). 성공 204·미소유/미존재 404. */
    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(@PathVariable Long addressId) {
        Long userId = authenticatedUserResolver.requireUserId();
        userAddressService.delete(userId, addressId);
        return ResponseEntity.noContent().build();
    }

    /** 기본 배송지 설정(Track 58 BL-4). 성공 204·미소유/미존재 404. */
    @PatchMapping("/{addressId}/default")
    public ResponseEntity<Void> setDefault(@PathVariable Long addressId) {
        Long userId = authenticatedUserResolver.requireUserId();
        userAddressService.setDefault(userId, addressId);
        return ResponseEntity.noContent().build();
    }
}
