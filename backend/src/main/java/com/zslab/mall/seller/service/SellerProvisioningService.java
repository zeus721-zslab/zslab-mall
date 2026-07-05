package com.zslab.mall.seller.service;

import com.zslab.mall.audit.enums.AuditLogAction;
import com.zslab.mall.audit.service.AuditContext;
import com.zslab.mall.audit.service.AuditRecorder;
import com.zslab.mall.auth.entity.Role;
import com.zslab.mall.auth.enums.RoleCode;
import com.zslab.mall.auth.repository.RoleRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.seller.controller.request.SellerProvisioningRequest;
import com.zslab.mall.seller.controller.response.SellerProvisioningResponse;
import com.zslab.mall.seller.entity.Seller;
import com.zslab.mall.seller.entity.SellerUser;
import com.zslab.mall.seller.enums.SellerStatus;
import com.zslab.mall.seller.exception.SellerUserAlreadyExistsException;
import com.zslab.mall.seller.repository.SellerRepository;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.user.exception.UserNotFoundException;
import com.zslab.mall.user.repository.UserRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 provisioning Application Service(Track 37·관리자 주도 A 경로). seller 입점 INSERT + 최초 owner seller_user
 * INSERT를 단일 트랜잭션으로 원자 수행한다. 트랜잭션 경계는 메서드 단위다.
 *
 * <p>중복 소속(V12 user_id 단독 UNIQUE·"1 user = 1 seller") 가드는 DB 제약을 최종 방어선으로 삼는다(옵션 A): pre-check
 * 대신 seller 저장 후 seller_user {@code saveAndFlush}가 던지는 {@link DataIntegrityViolationException}을 409로 변환하며,
 * 던진 예외가 {@code @Transactional} 경계를 넘어 seller INSERT까지 원자적으로 롤백한다. pre-check 방식은 409 경로에서
 * seller INSERT가 발생하지 않아 원자성을 실증할 수 없어 채택하지 않는다(D-121 연장·Track 37 결정).
 *
 * <p>role 배선은 seed된 SELLER_OWNER Role(V11)을 재사용한다({@link com.zslab.mall.user.service.UserService} BUYER 패턴 정합).
 */
@Slf4j
@Service
@Transactional
public class SellerProvisioningService {

    private final SellerRepository sellerRepository;
    private final SellerUserRepository sellerUserRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final AuditRecorder auditRecorder;

    public SellerProvisioningService(
            SellerRepository sellerRepository,
            SellerUserRepository sellerUserRepository,
            RoleRepository roleRepository,
            UserRepository userRepository,
            AuditRecorder auditRecorder) {
        this.sellerRepository = sellerRepository;
        this.sellerUserRepository = sellerUserRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 판매자 입점 provisioning. 성공 시 seller와 최초 owner seller_user(role=SELLER_OWNER) 매핑을 생성한다.
     *
     * @param request 입점 정보 요청
     * @param auditContext 감사 행위자 컨텍스트(운영자)
     * @throws UserNotFoundException ownerUserId에 해당하는 User가 없는 경우(404)
     * @throws IllegalArgumentException 초기 status가 PENDING·ACTIVE가 아닌 경우(400)
     * @throws IllegalStateException SELLER_OWNER Role seed가 없는 경우(내부 오류·500)
     * @throws SellerUserAlreadyExistsException ownerUserId가 이미 다른 판매자에 소속된 경우(409·V12 위반)
     */
    public SellerProvisioningResponse provision(SellerProvisioningRequest request, AuditContext auditContext) {
        if (!userRepository.existsById(request.ownerUserId())) {
            throw new UserNotFoundException("판매자 owner로 지정한 User가 없습니다: userId=" + request.ownerUserId());
        }

        validateInitialStatus(request.status());

        Role ownerRole = roleRepository.findByCode(RoleCode.SELLER_OWNER)
                .orElseThrow(() -> new IllegalStateException("SELLER_OWNER Role seed 누락(V11 마이그레이션 확인 필요)."));

        Seller seller = sellerRepository.save(Seller.create(
                request.companyName(),
                request.businessNo(),
                request.ceoName(),
                request.contactEmail(),
                request.contactPhone(),
                request.status()));

        try {
            // saveAndFlush로 uk_seller_user_user_id(V12) 위반을 트랜잭션 내에서 즉시 표면화한다. 위반 시 아래 catch가
            // 409로 변환하며, 던진 예외가 @Transactional 경계를 넘어 seller INSERT까지 원자적으로 롤백한다.
            sellerUserRepository.saveAndFlush(SellerUser.create(seller, request.ownerUserId(), ownerRole.getId()));
        } catch (DataIntegrityViolationException exception) {
            log.warn("[SellerProvisioning] 중복 소속 차단(409) ownerUserId={} rolledBackSellerPublicId={}",
                    request.ownerUserId(), seller.getPublicId());
            throw new SellerUserAlreadyExistsException(
                    "이미 다른 판매자에 소속된 사용자입니다: userId=" + request.ownerUserId());
        }

        // record는 seller/seller_user 저장이 모두 성공한 경로에만 둔다(catch 밖). 409 롤백 시 감사도 함께 롤백되나,
        // 롤백된 입점에 감사가 남지 않도록 성공 경로에서만 호출한다. 입점=생성이라 before={}·after=최소셋이다(D-139).
        // 대상은 입점 생성 본체인 SELLER(targetId=seller.id·해석 A). companyName·businessNo는 최소셋·AUD-2 민감정보 회피로 제외한다.
        auditRecorder.record(auditContext, AuditLogAction.CREATE, PolymorphicTargetType.SELLER, seller.getId(),
                Map.of(),
                Map.of("sellerPublicId", seller.getPublicId(), "status", request.status().name(),
                        "ownerUserId", request.ownerUserId()));
        log.info("[SellerProvisioning] 입점 완료 sellerPublicId={} ownerUserId={}",
                seller.getPublicId(), request.ownerUserId());
        return new SellerProvisioningResponse(seller.getPublicId());
    }

    /**
     * 생성 가능한 초기 판매자 상태는 PENDING·ACTIVE뿐이다. SUSPENDED·TERMINATED는 운영 이력(정지·해지) 전제라
     * 입점 생성 시점에 지정될 수 없다(Lifecycle invariant). 형식(@NotNull·유효 enum)은 DTO가, 값 제한은 도메인 규칙이라
     * 본 Service가 검증한다(CLAUDE.md 도메인 규칙 = Service 레이어).
     *
     * @throws IllegalArgumentException status가 PENDING·ACTIVE가 아닌 경우(400)
     */
    private void validateInitialStatus(SellerStatus status) {
        if (status != SellerStatus.PENDING && status != SellerStatus.ACTIVE) {
            throw new IllegalArgumentException("입점 생성 가능한 초기 상태는 PENDING·ACTIVE뿐입니다: " + status);
        }
    }
}
