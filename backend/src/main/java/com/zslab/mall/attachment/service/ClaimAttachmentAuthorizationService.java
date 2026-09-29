package com.zslab.mall.attachment.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.file.service.ImageFormat;
import com.zslab.mall.file.service.ImageUploadService;
import com.zslab.mall.order.repository.OrderItemRepository;
import com.zslab.mall.seller.repository.SellerMembershipProjection;
import com.zslab.mall.seller.repository.SellerUserRepository;
import com.zslab.mall.seller.service.SellerAccessPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 첨부(반품 사진) 열람 인가(Track 82 D-176·D-235 PR3 K5). 서빙 요청 키로 attachment 행을 찾고, 필터가 인증한 주체와 경로의 역할로
 * 판정한다. 거부·미존재는 전부 "열람 불가"(호출부 404)로 수렴시켜 파일 존재 여부를 드러내지 않는다.
 *
 * <p><b>열람 규칙</b>
 * <ul>
 *   <li>첨부 행의 target_type이 CLAIM이 아니면 거부(클레임 서빙 경로에 다른 대상의 첨부가 섞일 수 없다).</li>
 *   <li>연결된 첨부(target_id NOT NULL): 대상 클레임을 조회해 {@code claim.requested_by == 주체}(BUYER)이거나 ADMIN. 클레임이 없으면 거부.
 *       uploaded_by는 연결 첨부 판정에 쓰지 않는다(외부 검토 반영·연결 경로가 바뀌어도 소유 기준은 클레임 요청자).</li>
 *   <li>미연결 첨부(target_id NULL): 업로더 본인(BUYER·uploaded_by)만. ADMIN도 불가(아직 어떤 클레임에도 속하지 않은 개인 사진).</li>
 *   <li>SELLER(Track 90-D-1·D-176 §8 이월 종결): 연결 첨부만, {@code claim.order_item_id → order_item.seller_id}가 요청 user의 셀러
 *       소속({@code seller_user}·세션 허용 상태)과 일치할 때. 저장 키에는 sellerId가 없으므로 경로 문자열이 아니라 DB 경로로만 판정한다.
 *       미연결 첨부는 어떤 클레임에도 속하지 않아 셀러 불가.</li>
 *   <li>썸네일({@code _thumb}) 키는 원본 첨부 행의 규칙을 그대로 따른다. 후보 조회 결과는 정확히 1행이어야 하며 0·2행 이상은 거부.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ClaimAttachmentAuthorizationService {

    private static final String THUMBNAIL_SUFFIX = "_thumb";

    private final AttachmentRepository attachmentRepository;
    private final ClaimRepository claimRepository;
    private final OrderItemRepository orderItemRepository;
    private final SellerUserRepository sellerUserRepository;

    public ClaimAttachmentAuthorizationService(AttachmentRepository attachmentRepository, ClaimRepository claimRepository,
            OrderItemRepository orderItemRepository, SellerUserRepository sellerUserRepository) {
        this.attachmentRepository = attachmentRepository;
        this.claimRepository = claimRepository;
        this.orderItemRepository = orderItemRepository;
        this.sellerUserRepository = sellerUserRepository;
    }

    /**
     * 필터가 인증한 주체 1명으로 열람 규칙을 판정한다(D-235 PR3 K5 — 구매자 경로·셀러·관리자 별칭 공통).
     *
     * @param relativeKey 서빙 요청 키(예 {@code claims/2026/09/{ULID}.jpg} 또는 {@code ..._thumb.jpg})
     * @param role 경로가 고정한 역할(경로 hasRole로 인증 주체의 역할과 같음이 보장된다)
     * @return 열람 권한이 있으면 true. 첨부 행이 정확히 1건이 아니거나 대상이 CLAIM이 아니면 false
     */
    public boolean canView(String relativeKey, Long actorId, ActorRole role) {
        Optional<ViewTarget> target = findViewTarget(relativeKey);
        if (target.isEmpty()) {
            return false;
        }
        boolean allowed = isAllowed(target.get().attachment(), target.get().claim(), actorId, role);
        if (!allowed) {
            log.debug("[ClaimAttachmentAuthz] 열람 거부 key={} attachmentId={} role={}", relativeKey,
                    target.get().attachment().getPublicId(), role);
        }
        return allowed;
    }

    /** 요청 키 → 첨부 행(정확히 1건·CLAIM 대상) + 연결 클레임. 조건을 벗어나면 empty(호출부 404). */
    private Optional<ViewTarget> findViewTarget(String relativeKey) {
        List<String> filePaths = originalFilePathCandidates(relativeKey);
        if (filePaths.isEmpty()) {
            log.debug("[ClaimAttachmentAuthz] 지원하지 않는 썸네일 확장자 key={}", relativeKey);
            return Optional.empty();
        }
        List<Attachment> found = attachmentRepository.findByFilePathIn(filePaths);
        if (found.size() != 1) {
            log.debug("[ClaimAttachmentAuthz] 첨부 행 수 불일치 key={} found={}", relativeKey, found.size());
            return Optional.empty();
        }
        Attachment attachment = found.get(0);
        if (attachment.getTargetType() != PolymorphicTargetType.CLAIM) {
            log.debug("[ClaimAttachmentAuthz] 대상 유형 불일치 key={} attachmentId={} targetType={}", relativeKey,
                    attachment.getPublicId(), attachment.getTargetType());
            return Optional.empty();
        }
        if (!attachment.isLinked()) {
            return Optional.of(new ViewTarget(attachment, null));
        }
        Optional<Claim> claim = claimRepository.findById(attachment.getTargetId());
        if (claim.isEmpty()) {
            log.debug("[ClaimAttachmentAuthz] 대상 클레임 없음 key={} attachmentId={} claimId={}", relativeKey,
                    attachment.getPublicId(), attachment.getTargetId());
            return Optional.empty();
        }
        return Optional.of(new ViewTarget(attachment, claim.get()));
    }

    /** @param claim 연결 첨부의 대상 클레임. 미연결이면 null */
    private record ViewTarget(Attachment attachment, Claim claim) {
    }

    /** @param claim 연결 첨부의 대상 클레임(소유 기준 requested_by·셀러 기준 order_item_id). 미연결이면 null */
    private boolean isAllowed(Attachment attachment, Claim claim, Long actorId, ActorRole role) {
        boolean buyer = role == ActorRole.BUYER;
        if (!attachment.isLinked()) {
            return buyer && actorId.equals(attachment.getUploadedBy());
        }
        if ((buyer && actorId.equals(claim.getRequestedBy())) || role == ActorRole.ADMIN) {
            return true;
        }
        return role == ActorRole.SELLER && isOwningSeller(claim, actorId);
    }

    /**
     * 셀러 후보 판정(Track 90-D-1): user → seller_user 소속(세션 허용 상태·{@code HeaderSellerActorResolver}와 같은 조회·상태 기준) →
     * 그 seller.id가 클레임 품목의 seller_id와 같을 때만 허용. 소속 없음·PENDING/TERMINATED·품목 부재·타 셀러는 전부 false(호출부 404).
     */
    private boolean isOwningSeller(Claim claim, Long userId) {
        Optional<SellerMembershipProjection> membership = sellerUserRepository.findMembershipByUserId(userId);
        if (membership.isEmpty() || !SellerAccessPolicy.isSessionAllowed(membership.get().getStatus())) {
            return false;
        }
        Long sellerId = membership.get().getSellerId();
        return orderItemRepository.findById(claim.getOrderItemId())
                .map(item -> item.getSellerId().equals(sellerId))
                .orElse(false);
    }

    /**
     * 요청 키를 file_path 저장값(서빙 URL)으로 되돌린다. 썸네일 키 {@code {base}_thumb.{thumbExt}}는 원본 확장자를 알 수 없어(webp 원본의
     * 썸네일도 png) thumbExt를 쓰는 모든 형식의 원본 URL을 후보로 만든다. thumbExt가 어느 형식의 썸네일 확장자도 아니면 후보 없음(빈 목록).
     */
    private static List<String> originalFilePathCandidates(String relativeKey) {
        int dot = relativeKey.lastIndexOf('.');
        if (dot < 0) {
            return List.of(ImageUploadService.URL_PREFIX + relativeKey);
        }
        String base = relativeKey.substring(0, dot);
        if (!base.endsWith(THUMBNAIL_SUFFIX)) {
            return List.of(ImageUploadService.URL_PREFIX + relativeKey);
        }
        String extension = relativeKey.substring(dot + 1);
        String originalBase = base.substring(0, base.length() - THUMBNAIL_SUFFIX.length());
        List<String> candidates = new ArrayList<>();
        for (ImageFormat format : ImageFormat.values()) {
            if (format.thumbnailExtension().equals(extension)) {
                candidates.add(ImageUploadService.URL_PREFIX + originalBase + "." + format.extension());
            }
        }
        return candidates;
    }
}
