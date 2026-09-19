package com.zslab.mall.user.service;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.auth.repository.UserRoleRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.grade.entity.BuyerGrade;
import com.zslab.mall.grade.enums.BuyerGradeCode;
import com.zslab.mall.grade.repository.BuyerGradeRepository;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.repository.BuyerLastPaidProjection;
import com.zslab.mall.order.repository.OrderRepository;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.user.controller.request.AdminMemberSort;
import com.zslab.mall.user.controller.request.AdminMemberStatusFilter;
import com.zslab.mall.user.controller.response.AdminMemberDetailResponse;
import com.zslab.mall.user.controller.response.AdminMemberSummaryResponse;
import com.zslab.mall.user.entity.BuyerProfile;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.UserNotFoundException;
import com.zslab.mall.user.repository.AdminMemberSpecifications;
import com.zslab.mall.user.repository.BuyerProfileRepository;
import com.zslab.mall.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * 관리자 회원 조회(Track 84·{@code AdminOrderQueryService} 패턴). 대상은 BUYER role 보유 회원이며 목록은 Specification 페이지 +
 * 페이지 id 배치 enrich(등급·최근 결제일)로 N+1을 피한다. 비대상·미존재 publicId는 404로 통일한다.
 */
@Service
@Transactional(readOnly = true)
public class AdminMemberQueryService {

    private static final int MAX_KEYWORD_LENGTH = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final BuyerProfileRepository buyerProfileRepository;
    private final BuyerGradeRepository buyerGradeRepository;
    private final OrderRepository orderRepository;
    private final UserAddressService userAddressService;
    private final SellerUserRepository sellerUserRepository;
    private final SellerRepository sellerRepository;
    private final RoleRepository roleRepository;

    public AdminMemberQueryService(UserRepository userRepository, UserRoleRepository userRoleRepository,
            BuyerProfileRepository buyerProfileRepository, BuyerGradeRepository buyerGradeRepository,
            OrderRepository orderRepository, UserAddressService userAddressService,
            SellerUserRepository sellerUserRepository, SellerRepository sellerRepository, RoleRepository roleRepository) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.buyerProfileRepository = buyerProfileRepository;
        this.buyerGradeRepository = buyerGradeRepository;
        this.orderRepository = orderRepository;
        this.userAddressService = userAddressService;
        this.sellerUserRepository = sellerUserRepository;
        this.sellerRepository = sellerRepository;
        this.roleRepository = roleRepository;
    }

    /**
     * 관리자 회원 목록. keyword는 이름·이메일·연락처 부분일치.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     */
    public PagedResponse<AdminMemberSummaryResponse> listMembers(
            AdminMemberStatusFilter status, String keyword, AdminMemberSort sort, int page, int size) {
        Specification<User> specification = Specification
                .where(AdminMemberSpecifications.buyerRole())
                .and(AdminMemberSpecifications.status(status))
                .and(AdminMemberSpecifications.keyword(toLikePattern(normalizeKeyword(keyword))));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), toSort(sort));
        Page<User> userPage = userRepository.findAll(specification, pageable);

        List<Long> userIds = userPage.getContent().stream().map(User::getId).toList();
        Map<Long, BuyerGradeCode> gradeCodeByUserId = gradeCodeByUserId(userIds);
        Map<Long, LocalDateTime> lastPaidAtByUserId = userIds.isEmpty() ? Map.of()
                : orderRepository.findLastPaidAtByBuyerIdIn(userIds).stream()
                        .collect(Collectors.toMap(BuyerLastPaidProjection::getBuyerId, BuyerLastPaidProjection::getLastPaidAt));

        List<AdminMemberSummaryResponse> rows = userPage.getContent().stream()
                .map(user -> new AdminMemberSummaryResponse(
                        user.getPublicId(), user.getName(), user.getEmail(), user.getPhone(),
                        gradeCodeByUserId.get(user.getId()), user.getCreatedAt(),
                        lastPaidAtByUserId.get(user.getId()), user.getWithdrawnAt()))
                .toList();
        Page<AdminMemberSummaryResponse> rowPage = new PageImpl<>(rows, pageable, userPage.getTotalElements());
        return PagedResponse.from(rowPage);
    }

    /**
     * 관리자 회원 상세. 등급(buyer_profile)·배송지(user_address)를 함께 반환한다.
     *
     * @throws UserNotFoundException publicId 미존재·BUYER role 미보유(404)
     */
    public AdminMemberDetailResponse getMember(String publicId) {
        User user = requireBuyer(publicId);
        BuyerProfile profile = buyerProfileRepository.findById(user.getId()).orElse(null);
        AdminMemberDetailResponse.Grade grade = profile == null ? null
                : new AdminMemberDetailResponse.Grade(
                        gradeCodeByUserId(List.of(user.getId())).get(user.getId()),
                        profile.getGradeSource(), profile.getGradeLockedUntil());
        return new AdminMemberDetailResponse(
                user.getPublicId(), user.getName(), user.getEmail(), user.getPhone(), user.getCreatedAt(),
                user.getWithdrawnAt(), user.isPasswordChangeRequired(), grade, userAddressService.list(user.getId()),
                sellerMembership(user));
    }

    /**
     * 셀러 소속(Track 89-G STEP 498·D-189). seller_user는 user_id 단독 UK라 최대 1건({@code findByUserId} 1쿼리). soft-delete 셀러는
     * {@code SellerRepository.findById}가 걸러 null. lastActiveMember는 역할 무관 활성 구성원 수로 판정한다 — 셀러 로그인은 seller_user
     * 행 존재만으로 결정되고 역할은 권한에 관여하지 않으므로(D-189 §1-A 2) "탈퇴하면 로그인할 수 있는 사람이 없어지는가"는 이 회원이 활성이고
     * 그 외 활성 구성원이 0명인가와 같다. 회원이 이미 탈퇴했으면 false. 상세 1건당 고정 4쿼리(소속·셀러·역할·활성 수)이며 목록엔 싣지 않는다.
     */
    private AdminMemberDetailResponse.SellerMembership sellerMembership(User user) {
        SellerUser sellerUser = sellerUserRepository.findByUserId(user.getId()).orElse(null);
        if (sellerUser == null) {
            return null;
        }
        Seller seller = sellerRepository.findById(sellerUser.getSeller().getId()).orElse(null);
        if (seller == null) {
            return null;
        }
        RoleCode roleCode = roleRepository.findById(sellerUser.getRoleId()).map(Role::getCode).orElse(null);
        boolean lastActiveMember = user.getWithdrawnAt() == null
                && sellerUserRepository.countActiveBySellerId(seller.getId()) == 1;
        return new AdminMemberDetailResponse.SellerMembership(seller.getPublicId(), seller.getCompanyName(), roleCode, lastActiveMember);
    }

    /**
     * publicId → BUYER 회원 해소. 관리자 명령 서비스도 같은 404 규약을 쓴다.
     *
     * @throws UserNotFoundException publicId 미존재·BUYER role 미보유(404 통일·비대상 은닉)
     */
    public User requireBuyer(String publicId) {
        return findBuyer(publicId)
                .orElseThrow(() -> new UserNotFoundException("회원을 찾을 수 없습니다: publicId=" + publicId));
    }

    /**
     * publicId → BUYER 회원 id 해소(예외 없는 판정). 관리자 주문·클레임 목록의 buyerPublicId 필터가 공유하며, 미존재·비BUYER는
     * "매칭 없음"(empty)으로 돌려 호출자가 빈 페이지를 내게 한다(관리자·판매자 계정 publicId로 주문을 뒤지는 경로 차단).
     */
    public Optional<Long> findBuyerId(String publicId) {
        return findBuyer(publicId).map(User::getId);
    }

    private Optional<User> findBuyer(String publicId) {
        return userRepository.findByPublicId(publicId)
                .filter(user -> userRoleRepository.existsByUserIdAndRole_Code(user.getId(), RoleCode.BUYER));
    }

    /** buyer_profile.grade_id → buyer_grade.code 매핑(등급 마스터 3행은 findAll 1회). */
    private Map<Long, BuyerGradeCode> gradeCodeByUserId(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BuyerGradeCode> codeByGradeId = buyerGradeRepository.findAll().stream()
                .collect(Collectors.toMap(BuyerGrade::getId, BuyerGrade::getCode));
        return buyerProfileRepository.findByUserIdIn(userIds).stream()
                .filter(profile -> codeByGradeId.containsKey(profile.getGradeId()))
                .collect(Collectors.toMap(BuyerProfile::getUserId, profile -> codeByGradeId.get(profile.getGradeId())));
    }

    // ---------- 입력 정규화 (AdminOrderQueryService 정합) ----------

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

    private static Sort toSort(AdminMemberSort sort) {
        Sort.Direction direction = sort == AdminMemberSort.OLDEST ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, "createdAt").and(Sort.by(direction, "id"));
    }
}
