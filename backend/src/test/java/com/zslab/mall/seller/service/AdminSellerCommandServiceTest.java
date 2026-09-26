package com.zslab.mall.seller.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.common.security.DemoAccountGuard;
import com.zslab.mall.seller.controller.request.AdminSellerUpdateRequest;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.exception.SellerBusinessNoDuplicateException;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.seller.repository.WithdrawnSellerRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link AdminSellerCommandService#update} flush 무결성 위반 분기 단위 검증(Track 89-D 외부 검토 지적 2). uk_seller_business_no 위반만
 * 409 {@link SellerBusinessNoDuplicateException}으로 변환하고, 그 외 무결성 오류(다른 제약·메시지 없음)는 원래
 * {@link DataIntegrityViolationException}을 그대로 던져 GlobalExceptionHandler 기본 경로(500)로 흐르게 한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminSellerCommandServiceTest {

    private static final String SELLER_PID = "slr_TEST0000000000000000000AA";

    @Mock private SellerRepository sellerRepository;
    @Mock private WithdrawnSellerRepository withdrawnSellerRepository;
    @Mock private SellerTerminationGuard sellerTerminationGuard;
    @Mock private AdminSellerQueryService adminSellerQueryService;
    @Mock private AuditRecorder auditRecorder;
    @Mock private SellerUserRepository sellerUserRepository;
    @Mock private UserRepository userRepository;
    @Mock private DemoAccountGuard demoAccountGuard;

    private AdminSellerCommandService service;
    private final AuditContext auditContext = AuditContext.of(1L, "ADMIN");

    @BeforeEach
    void setUp() {
        service = new AdminSellerCommandService(sellerRepository, withdrawnSellerRepository, sellerTerminationGuard,
                adminSellerQueryService, auditRecorder, sellerUserRepository, userRepository, demoAccountGuard);
    }

    /** 선검사(existsByBusinessNo=false) 통과 후 flush 레이스 분기로 들어가는 픽스처. 호출마다 새 Seller(앞 호출의 update 반영 방지). */
    private void stubSeller(String newBusinessNo) {
        Seller seller = Seller.create("상호", "111-11-11111", "대표", null, null, SellerStatus.ACTIVE);
        when(sellerRepository.findByPublicIdForUpdate(SELLER_PID)).thenReturn(Optional.of(seller));
        when(sellerRepository.existsByBusinessNo(newBusinessNo)).thenReturn(false);
    }

    private static AdminSellerUpdateRequest changeBusinessNo(String businessNo) {
        return new AdminSellerUpdateRequest("상호", businessNo, "대표", null, null, null, null);
    }

    private static DataIntegrityViolationException violation(String causeMessage) {
        return new DataIntegrityViolationException("could not execute statement",
                causeMessage == null ? null : new RuntimeException(causeMessage));
    }

    @Test
    @DisplayName("flush가 uk_seller_business_no 위반 → SellerBusinessNoDuplicateException(409)·감사 미기록")
    void update_flushBusinessNoUnique_throws409() {
        stubSeller("222-22-22222");
        doThrow(violation("(conn=1) Duplicate entry '222-22-22222' for key 'uk_seller_business_no'"))
                .when(sellerRepository).flush();

        assertThatThrownBy(() -> service.update(SELLER_PID, changeBusinessNo("222-22-22222"), auditContext))
                .isInstanceOf(SellerBusinessNoDuplicateException.class);
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("flush가 다른 무결성 오류(FK·다른 UK·원인 메시지 없음) → DataIntegrityViolationException 그대로 전파(409 아님)")
    void update_flushOtherIntegrityViolation_rethrows() {
        stubSeller("222-22-22222");
        doThrow(violation("Cannot add or update a child row: a foreign key constraint fails (fk_seller_user_seller)"))
                .when(sellerRepository).flush();
        assertThatThrownBy(() -> service.update(SELLER_PID, changeBusinessNo("222-22-22222"), auditContext))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(SellerBusinessNoDuplicateException.class);

        stubSeller("333-33-33333");
        doThrow(violation(null)).when(sellerRepository).flush();
        assertThatThrownBy(() -> service.update(SELLER_PID, changeBusinessNo("333-33-33333"), auditContext))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(SellerBusinessNoDuplicateException.class);
        verify(auditRecorder, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("판별 헬퍼: UK 이름 포함 메시지만 true·cause 없음/다른 메시지 false")
    void discriminator() {
        assertThat(SellerConstraintViolations.isBusinessNoDuplicate(
                violation("Duplicate entry 'x' for key 'uk_seller_business_no'"))).isTrue();
        assertThat(SellerConstraintViolations.isBusinessNoDuplicate(
                violation("Duplicate entry 'x' for key 'uk_seller_user_user_id'"))).isFalse();
        assertThat(SellerConstraintViolations.isBusinessNoDuplicate(violation(null))).isFalse();
    }
}
