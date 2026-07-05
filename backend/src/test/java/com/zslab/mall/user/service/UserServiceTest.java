package com.zslab.mall.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.user.controller.request.ChangePasswordRequest;
import com.zslab.mall.user.controller.request.SignupRequest;
import com.zslab.mall.user.controller.response.SignupResponse;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.EmailAlreadyExistsException;
import com.zslab.mall.user.policy.PasswordPolicy;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link UserService} 오케스트레이션 검증(Mockito). 정상 가입·email 중복·password 정책 위반.
 *
 * <p>save 후 id·publicId는 DB IDENTITY·{@code @PrePersist} 생성이라 mock에는 없다. UserRole.create의 userId 가드
 * 충족과 응답 publicId 검증을 위해 save stub에서 {@link ReflectionTestUtils}로 주입한다(실 저장 검증은 통합 테스트 소관).
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final String EMAIL = "signup@zslab.test";
    private static final String NAME = "홍길동";
    private static final String PHONE = "010-1234-5678";
    private static final String PASSWORD = "password123";
    private static final long USER_ID = 7001L;
    private static final String PUBLIC_ID = "usr_SIGNUP0000000000000000000000";
    private static final long SILVER_GRADE_ID = 1L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private BuyerProfileRepository buyerProfileRepository;
    @Mock
    private BuyerGradeRepository buyerGradeRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private PasswordPolicy passwordPolicy;

    @InjectMocks
    private UserService userService;

    private SignupRequest request() {
        return new SignupRequest(EMAIL, NAME, PHONE, PASSWORD);
    }

    // 실 DB IDENTITY 대신 id를 주입한다(register가 silver.getId()로 BuyerProfile.gradeId를 채우므로 null이면 팩토리 가드 위반).
    private BuyerGrade silverGrade() {
        BuyerGrade grade = BuyerGrade.create(BuyerGradeCode.SILVER, "실버");
        ReflectionTestUtils.setField(grade, "id", SILVER_GRADE_ID);
        return grade;
    }

    @Test
    @DisplayName("정상 가입: User 저장·passwordHash 인코딩·BUYER UserRole 저장·publicId 반환")
    void register_happyPath() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", USER_ID);
            ReflectionTestUtils.setField(user, "publicId", PUBLIC_ID);
            return user;
        });
        when(roleRepository.findByCode(RoleCode.BUYER))
                .thenReturn(Optional.of(Role.create(RoleCode.BUYER, "구매자")));
        when(buyerGradeRepository.findByCode(BuyerGradeCode.SILVER))
                .thenReturn(Optional.of(silverGrade()));

        SignupResponse response = userService.register(request());

        assertThat(response.userPublicId()).isEqualTo(PUBLIC_ID);
        verify(passwordEncoder).encode(PASSWORD);
        verify(userRepository).save(any(User.class));
        verify(userRoleRepository).save(any(UserRole.class));
        verify(buyerProfileRepository).save(any(BuyerProfile.class));
    }

    @Test
    @DisplayName("email 중복: existsByEmail true → EmailAlreadyExistsException·저장 없음")
    void register_duplicateEmail_throws() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThatThrownBy(() -> userService.register(request()))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(userRoleRepository, never()).save(any(UserRole.class));
    }

    @Test
    @DisplayName("password 정책 위반: PasswordPolicy 예외 전파·중복검사 이전 차단")
    void register_weakPassword_throws() {
        doThrow(new IllegalArgumentException("비밀번호는 최소 8자 이상이어야 합니다."))
                .when(passwordPolicy).validate(any());

        assertThatThrownBy(() -> userService.register(request()))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).existsByEmail(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("SILVER BuyerGrade seed 부재: findByCode empty → IllegalStateException·BuyerProfile 저장 없음")
    void register_missingSilverGradeSeed_throws() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn("hashed-pw");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", USER_ID);
            ReflectionTestUtils.setField(user, "publicId", PUBLIC_ID);
            return user;
        });
        when(roleRepository.findByCode(RoleCode.BUYER))
                .thenReturn(Optional.of(Role.create(RoleCode.BUYER, "구매자")));
        when(buyerGradeRepository.findByCode(BuyerGradeCode.SILVER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.register(request()))
                .isInstanceOf(IllegalStateException.class);

        verify(buyerProfileRepository, never()).save(any(BuyerProfile.class));
    }

    @Test
    @DisplayName("비번변경 정상: 현재 비번 일치·정책 통과 → hash 교체·save")
    void changePassword_success() {
        User user = User.create(EMAIL, NAME, PHONE);
        user.assignPasswordHash("old-hash");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-pw", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        userService.changePassword(USER_ID, new ChangePasswordRequest("current-pw", "new-password"));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("비번변경 현재 비번 불일치: matches false → IllegalArgumentException·저장 없음")
    void changePassword_wrongCurrent_throws() {
        User user = User.create(EMAIL, NAME, PHONE);
        user.assignPasswordHash("old-hash");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-pw", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(USER_ID,
                new ChangePasswordRequest("wrong-pw", "new-password")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("비번변경 새 비번 정책 위반: PasswordPolicy 예외 전파·저장 없음")
    void changePassword_weakNew_throws() {
        User user = User.create(EMAIL, NAME, PHONE);
        user.assignPasswordHash("old-hash");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-pw", "old-hash")).thenReturn(true);
        doThrow(new IllegalArgumentException("정책 위반")).when(passwordPolicy).validate("short");

        assertThatThrownBy(() -> userService.changePassword(USER_ID,
                new ChangePasswordRequest("current-pw", "short")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("비번변경 자격증명 미생성(hash null): matches 미호출·IllegalArgumentException(400 통일)")
    void changePassword_nullHash_throws() {
        User user = User.create(EMAIL, NAME, PHONE); // passwordHash null
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.changePassword(USER_ID,
                new ChangePasswordRequest("any-pw", "new-password")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(passwordEncoder, never()).matches(any(), any());
        verify(userRepository, never()).save(any(User.class));
    }
}
