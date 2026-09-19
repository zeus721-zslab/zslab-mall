package com.zslab.mall.seller.service;

import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.common.exception.MalformedRequestException;
import com.zslab.mall.order.controller.response.PagedResponse;
import com.zslab.mall.order.enums.OrderItemStatus;
import com.zslab.mall.order.enums.OrderStatus;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.order.repository.SellerSalesSummaryProjection;
import com.zslab.mall.product.enums.ProductStatus;
import com.zslab.mall.product.repository.ProductRepository;
import com.zslab.mall.product.repository.SellerProductCountProjection;
import com.zslab.mall.seller.controller.response.AdminSellerBankAccountResponse;
import com.zslab.mall.seller.controller.response.AdminSellerDetailResponse;
import com.zslab.mall.seller.controller.response.AdminSellerSummaryResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerBankAccount;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.exception.SellerNotFoundException;
import com.zslab.mall.seller.repository.AdminSellerSpecifications;
import com.zslab.mall.seller.repository.SellerBankAccountRepository;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.settlement.repository.SettlementRepository;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.repository.UserRepository;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 * 관리자 셀러 조회(Track 89-D·{@code AdminMemberQueryService} 패턴). 목록은 Specification 페이지 + 페이지 id 배치 enrich(상품 수·
 * 주 계좌 여부)로 N+1을 피하고, 상세는 구성원·계좌·집계·종료 가능 여부({@link SellerTerminationGuard} — 실제 전이와 같은 판정)를
 * 조립한다. 기존 드롭다운용 {@code SellerQueryService.listAll}은 그대로 둔다(소비처 호환).
 */
@Service
@Transactional(readOnly = true)
public class AdminSellerQueryService {

    private static final int MAX_KEYWORD_LENGTH = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int ACCOUNT_SUFFIX_LENGTH = 4;
    /** 주문 수 집계에서 제외하는 "결제 이력 없음" 주문 상태. */
    private static final Set<OrderStatus> UNPAID_ORDER_STATUSES =
            Set.of(OrderStatus.PENDING_PAYMENT, OrderStatus.PAYMENT_EXPIRED);

    private final SellerRepository sellerRepository;
    private final SellerUserRepository sellerUserRepository;
    private final SellerBankAccountRepository sellerBankAccountRepository;
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final SettlementRepository settlementRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final SellerTerminationGuard sellerTerminationGuard;

    public AdminSellerQueryService(
            SellerRepository sellerRepository,
            SellerUserRepository sellerUserRepository,
            SellerBankAccountRepository sellerBankAccountRepository,
            ProductRepository productRepository,
            OrderItemRepository orderItemRepository,
            SettlementRepository settlementRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            SellerTerminationGuard sellerTerminationGuard) {
        this.sellerRepository = sellerRepository;
        this.sellerUserRepository = sellerUserRepository;
        this.sellerBankAccountRepository = sellerBankAccountRepository;
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
        this.settlementRepository = settlementRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.sellerTerminationGuard = sellerTerminationGuard;
    }

    /**
     * 관리자 셀러 목록. keyword는 상호·사업자번호·담당자 이메일 부분일치, 정렬은 등록일 desc 고정.
     *
     * @throws MalformedRequestException keyword가 trim 후 {@value #MAX_KEYWORD_LENGTH}자를 초과할 때(400)
     */
    public PagedResponse<AdminSellerSummaryResponse> list(SellerStatus status, String keyword, int page, int size) {
        Specification<Seller> specification = Specification
                .where(AdminSellerSpecifications.status(status))
                .and(AdminSellerSpecifications.keyword(toLikePattern(normalizeKeyword(keyword))));
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Seller> sellerPage = sellerRepository.findAll(specification, pageable);

        List<Long> sellerIds = sellerPage.getContent().stream().map(Seller::getId).toList();
        Map<Long, Map<ProductStatus, Long>> productCounts = productCountsBySeller(sellerIds);
        Set<Long> sellerIdsHavingPrimary = sellerIds.isEmpty() ? Set.of()
                : new HashSet<>(sellerBankAccountRepository.findSellerIdsHavingPrimary(sellerIds));

        List<AdminSellerSummaryResponse> rows = sellerPage.getContent().stream()
                .map(seller -> new AdminSellerSummaryResponse(
                        seller.getPublicId(), seller.getCompanyName(), seller.getBusinessNo(), seller.getCeoName(),
                        seller.getContactEmail(), seller.getContactPhone(), seller.getStatus(),
                        total(productCounts.get(seller.getId())), sellerIdsHavingPrimary.contains(seller.getId()),
                        seller.getCreatedAt()))
                .toList();
        Page<AdminSellerSummaryResponse> rowPage = new PageImpl<>(rows, pageable, sellerPage.getTotalElements());
        return PagedResponse.from(rowPage);
    }

    /**
     * 관리자 셀러 상세.
     *
     * @throws SellerNotFoundException publicId 미존재·soft-delete(404)
     */
    public AdminSellerDetailResponse get(String sellerPublicId) {
        Seller seller = requireSeller(sellerPublicId);
        return toDetail(seller);
    }

    /** 엔티티 → 상세 응답 조립. 전이·수정 명령 서비스가 변경 직후 같은 형태로 응답하기 위해 공유한다. */
    public AdminSellerDetailResponse toDetail(Seller seller) {
        Long sellerId = seller.getId();
        Map<ProductStatus, Long> productCountByStatus =
                productCountsBySeller(List.of(sellerId)).getOrDefault(sellerId, new EnumMap<>(ProductStatus.class));
        List<SellerBankAccount> allAccounts = sellerBankAccountRepository.findAllBySellerId(sellerId);
        // 주 계좌는 목록에서 고른다(SLR-3·V32 UNIQUE로 최대 1건·방어적으로 첫 건). 별도 쿼리를 없애 상세 1회 조회로 충분하다.
        List<SellerBankAccount> primaryAccounts = allAccounts.stream().filter(SellerBankAccount::isPrimary).toList();
        AdminSellerDetailResponse.BankAccount primaryBankAccount =
                primaryAccounts.isEmpty() ? null : toBankAccount(primaryAccounts.get(0));
        // 정산 참조 여부(수정 가능 미리보기·외부 검토 Q6): existsByBankAccountId와 같은 기준을 배치 1쿼리로. 미리보기 = 수정 409 판정.
        Set<Long> referencedIds = allAccounts.isEmpty() ? Set.of() : new HashSet<>(settlementRepository.findReferencedBankAccountIds(
                allAccounts.stream().map(SellerBankAccount::getId).toList()));
        List<AdminSellerBankAccountResponse> bankAccounts = allAccounts.stream()
                .map(account -> AdminSellerBankAccountResponse.of(account, referencedIds.contains(account.getId())))
                .toList();
        SellerSalesSummaryProjection sales = orderItemRepository.summarizeSalesBySellerId(
                sellerId, UNPAID_ORDER_STATUSES, OrderItemStatus.CONFIRMED);
        List<AdminSellerDetailResponse.SettlementTotal> settlements =
                settlementRepository.sumByStatusForSeller(sellerId).stream()
                        .map(total -> new AdminSellerDetailResponse.SettlementTotal(
                                total.getStatus(), total.getSettlementCount(), total.getNetAmount()))
                        .toList();
        List<SellerTerminationBlock> terminationBlocks = sellerTerminationGuard.evaluate(sellerId);
        long saleProductCount = productCountByStatus.getOrDefault(ProductStatus.SALE, 0L);

        return new AdminSellerDetailResponse(
                seller.getPublicId(), seller.getCompanyName(), seller.getBusinessNo(), seller.getCeoName(),
                seller.getContactEmail(), seller.getContactPhone(), seller.getStatus(), seller.getCommissionRate(),
                seller.getCreatedAt(), seller.getUpdatedAt(),
                members(sellerId), primaryBankAccount, bankAccounts,
                total(productCountByStatus), productCountByStatus,
                sales.getOrderCount(), sales.getConfirmedAmount(),
                settlements,
                terminationBlocks.isEmpty(), terminationBlocks,
                new AdminSellerDetailResponse.Warnings(primaryBankAccount == null, saleProductCount));
    }

    /**
     * publicId → 셀러 해소(soft-delete는 @SQLRestriction으로 empty). 명령 서비스도 같은 404 규약을 쓴다.
     *
     * @throws SellerNotFoundException 미존재(404)
     */
    public Seller requireSeller(String sellerPublicId) {
        return sellerRepository.findByPublicId(sellerPublicId)
                .orElseThrow(() -> new SellerNotFoundException("셀러를 찾을 수 없습니다: " + sellerPublicId));
    }

    // ---------- enrich ----------

    private Map<Long, Map<ProductStatus, Long>> productCountsBySeller(List<Long> sellerIds) {
        if (sellerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<ProductStatus, Long>> result = new HashMap<>();
        for (SellerProductCountProjection row : productRepository.countActiveBySellerIdsGroupByStatus(sellerIds)) {
            result.computeIfAbsent(row.getSellerId(), ignored -> new EnumMap<>(ProductStatus.class))
                    .put(row.getStatus(), row.getProductCount());
        }
        return result;
    }

    private static long total(Map<ProductStatus, Long> countByStatus) {
        return countByStatus == null ? 0L : countByStatus.values().stream().mapToLong(Long::longValue).sum();
    }

    /** 구성원 배치 enrich: seller_user → user(findByIdIn·soft-delete 제외) + role(findAllById) 각 1쿼리. */
    private List<AdminSellerDetailResponse.Member> members(Long sellerId) {
        List<SellerUser> sellerUsers = sellerUserRepository.findBySellerId(sellerId);
        if (sellerUsers.isEmpty()) {
            return List.of();
        }
        Map<Long, User> userById = userRepository.findByIdIn(
                        sellerUsers.stream().map(SellerUser::getUserId).distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, RoleCode> roleCodeById = roleRepository.findAllById(
                        sellerUsers.stream().map(SellerUser::getRoleId).distinct().toList()).stream()
                .collect(Collectors.toMap(Role::getId, Role::getCode));
        return sellerUsers.stream()
                .map(sellerUser -> {
                    User user = userById.get(sellerUser.getUserId());
                    return new AdminSellerDetailResponse.Member(
                            user == null ? null : user.getPublicId(),
                            user == null ? null : user.getEmail(),
                            user == null ? null : user.getName(),
                            roleCodeById.get(sellerUser.getRoleId()),
                            user == null ? null : user.getWithdrawnAt());
                })
                .toList();
    }

    private static AdminSellerDetailResponse.BankAccount toBankAccount(SellerBankAccount account) {
        String number = account.getAccountNumber() == null ? "" : account.getAccountNumber();
        String suffix = number.length() <= ACCOUNT_SUFFIX_LENGTH
                ? number : number.substring(number.length() - ACCOUNT_SUFFIX_LENGTH);
        return new AdminSellerDetailResponse.BankAccount(account.getId(), account.getBankCode(),
                account.getAccountHolder(), suffix, account.getStatus(), account.getVerifiedAt());
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
