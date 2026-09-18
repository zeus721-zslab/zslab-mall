package com.zslab.mall.auth.service;

import com.zslab.mall.auth.controller.request.AdminOperatorRoleFilter;
import com.zslab.mall.auth.controller.response.AdminMeResponse;
import com.zslab.mall.auth.controller.response.AdminOperatorSummaryResponse;
import com.zslab.mall.auth.entity.UserRole;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.AdminOperatorSpecifications;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.user.controller.request.AdminMemberStatusFilter;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.UserNotFoundException;
import com.zslab.mall.user.repository.AdminMemberSpecifications;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 운영자 조회(Track 89-E·{@code AdminMemberQueryService} 패턴). 모수는 ADMIN 계열 역할(SUPER_ADMIN·ADMIN_OPERATOR) 보유 회원
 * — {@code DbRoleAuthorization}의 ADMIN 판정 집합과 동일하다. 목록은 Specification 페이지 + 페이지 id 배치 role 조회로 N+1을
 * 피하며, 일반회원 겸직은 같은 배치에서 BUYER role 보유로 판정한다(회원 목록 모수와 1:1). 조회 인가는 코어스 ADMIN 게이트뿐이다
 * (ADMIN_OPERATOR도 열람 가능·부여·회수만 SUPER_ADMIN·D-186).
 */
@Service
@Transactional(readOnly = true)
public class AdminOperatorQueryService {

    /** 운영자 모수 역할 — {@code DbRoleAuthorization.ADMIN_CODES}와 같은 집합(로그인 가능한 ADMIN과 1:1). */
    private static final Set<RoleCode> ADMIN_CODES = EnumSet.of(RoleCode.SUPER_ADMIN, RoleCode.ADMIN_OPERATOR);
    private static final int MAX_KEYWORD_LENGTH = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;

    public AdminOperatorQueryService(UserRepository userRepository, UserRoleRepository userRoleRepository) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
    }

    /**
     * 운영자 목록. role이 null이면 ADMIN 계열 전체, keyword는 이름·이메일 부분일치. 정렬은 가입일 desc 고정.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     */
    public PagedResponse<AdminOperatorSummaryResponse> listOperators(
            AdminOperatorRoleFilter role, AdminMemberStatusFilter status, String keyword, int page, int size) {
        Set<RoleCode> codes = role == null ? ADMIN_CODES : EnumSet.of(RoleCode.valueOf(role.name()));
        Specification<User> specification = Specification
                .where(AdminOperatorSpecifications.anyRole(codes))
                .and(AdminMemberSpecifications.status(status))
                .and(AdminOperatorSpecifications.keyword(toLikePattern(normalizeKeyword(keyword))));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<User> userPage = userRepository.findAll(specification, pageable);

        List<Long> userIds = userPage.getContent().stream().map(User::getId).toList();
        Map<Long, List<RoleCode>> rolesByUserId = rolesByUserId(userIds);

        List<AdminOperatorSummaryResponse> rows = userPage.getContent().stream()
                .map(user -> {
                    List<RoleCode> roles = rolesByUserId.getOrDefault(user.getId(), List.of());
                    return new AdminOperatorSummaryResponse(
                            user.getPublicId(), user.getName(), user.getEmail(),
                            roles.stream().filter(ADMIN_CODES::contains).toList(),
                            roles.contains(RoleCode.BUYER), user.getCreatedAt(), user.getWithdrawnAt());
                })
                .toList();
        Page<AdminOperatorSummaryResponse> rowPage = new PageImpl<>(rows, pageable, userPage.getTotalElements());
        return PagedResponse.from(rowPage);
    }

    /**
     * 현재 로그인한 관리자 자신. 보유 역할 전체와 SUPER_ADMIN 여부를 돌려준다(FE 버튼 활성 판정용·실인가는 명령 서비스가 강제).
     *
     * @throws UserNotFoundException 토큰의 userId에 해당하는 회원이 없는 경우(404·soft-delete 포함)
     */
    public AdminMeResponse me(Long callerUserId) {
        User user = userRepository.findById(callerUserId)
                .orElseThrow(() -> new UserNotFoundException("현재 관리자 회원을 찾을 수 없습니다: userId=" + callerUserId));
        List<RoleCode> roles = rolesByUserId(List.of(user.getId())).getOrDefault(user.getId(), List.of());
        return new AdminMeResponse(user.getPublicId(), user.getName(), user.getEmail(), roles,
                roles.contains(RoleCode.SUPER_ADMIN));
    }

    /** 페이지 userIds의 역할 매핑을 1쿼리(JOIN FETCH)로 묶어 RoleCode 선언 순으로 정렬한다. */
    private Map<Long, List<RoleCode>> rolesByUserId(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userRoleRepository.findWithRoleByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(UserRole::getUserId,
                        Collectors.mapping(userRole -> userRole.getRole().getCode(),
                                Collectors.collectingAndThen(Collectors.toList(),
                                        codes -> codes.stream().sorted(Comparator.naturalOrder()).toList()))));
    }

    // ---------- 입력 정규화 (AdminMemberQueryService 정합) ----------

    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            throw new MalformedRequestException("keyword는 최대 " + MAX_KEYWORD_LENGTH + "자입니다.");
        }
        return trimmed;
    }

    private static String toLikePattern(String trimmedKeyword) {
        if (trimmedKeyword == null) {
            return null;
        }
        String escaped = trimmedKeyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static int clampSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
