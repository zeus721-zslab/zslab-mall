package com.zslab.mall.attachment.service;

import com.zslab.mall.attachment.entity.Attachment;
import com.zslab.mall.attachment.repository.AttachmentRepository;
import com.zslab.mall.claim.entity.Claim;
import com.zslab.mall.claim.repository.ClaimRepository;
import com.zslab.mall.common.enums.PolymorphicTargetType;
import com.zslab.mall.common.security.ActorRole;
import com.zslab.mall.common.security.AuthenticatedUserStateVerifier;
import com.zslab.mall.common.security.TokenPayload;
import com.zslab.mall.common.security.TokenProvider;
import com.zslab.mall.file.service.ImageFormat;
import com.zslab.mall.file.service.ImageUploadService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클레임 첨부(반품 사진) 열람 인가(Track 82 D-176). 서빙 요청 키로 attachment 행을 찾고, 후보 토큰마다 독립 판정해 하나라도 허가되면 열람을
 * 허용한다. 거부·미존재·무효 토큰은 전부 "열람 불가"(호출부 404)로 수렴시켜 파일 존재 여부를 드러내지 않는다.
 *
 * <p><b>열람 규칙</b>
 * <ul>
 *   <li>첨부 행의 target_type이 CLAIM이 아니면 거부(클레임 서빙 경로에 다른 대상의 첨부가 섞일 수 없다).</li>
 *   <li>연결된 첨부(target_id NOT NULL): 대상 클레임을 조회해 {@code claim.requested_by == 주체}(BUYER)이거나 ADMIN. 클레임이 없으면 거부.
 *       uploaded_by는 연결 첨부 판정에 쓰지 않는다(외부 검토 반영·연결 경로가 바뀌어도 소유 기준은 클레임 요청자).</li>
 *   <li>미연결 첨부(target_id NULL): 업로더 본인(BUYER·uploaded_by)만. ADMIN도 불가(아직 어떤 클레임에도 속하지 않은 개인 사진).</li>
 *   <li>SELLER: 거부. 셀러 클레임 화면 트랙에서 claim→order_item.seller_id→seller_user 규칙으로 허용 예정(D-176 이월).</li>
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
    private final TokenProvider tokenProvider;
    private final AuthenticatedUserStateVerifier userStateVerifier;

    public ClaimAttachmentAuthorizationService(AttachmentRepository attachmentRepository, ClaimRepository claimRepository,
            TokenProvider tokenProvider, AuthenticatedUserStateVerifier userStateVerifier) {
        this.attachmentRepository = attachmentRepository;
        this.claimRepository = claimRepository;
        this.tokenProvider = tokenProvider;
        this.userStateVerifier = userStateVerifier;
    }

    /**
     * @param relativeKey 서빙 요청 키(예 {@code claims/2026/09/{ULID}.jpg} 또는 {@code ..._thumb.jpg})
     * @param candidateTokens Bearer → admin_token → auth_token 순서의 원시 토큰(검증 전)
     * @return 후보 중 하나라도 열람 권한이 있으면 true. 첨부 행이 정확히 1건이 아니거나 대상이 CLAIM이 아니면 false
     */
    public boolean canView(String relativeKey, List<String> candidateTokens) {
        List<String> filePaths = originalFilePathCandidates(relativeKey);
        if (filePaths.isEmpty()) {
            log.debug("[ClaimAttachmentAuthz] 지원하지 않는 썸네일 확장자 key={}", relativeKey);
            return false;
        }
        List<Attachment> found = attachmentRepository.findByFilePathIn(filePaths);
        if (found.size() != 1) {
            log.debug("[ClaimAttachmentAuthz] 첨부 행 수 불일치 key={} found={}", relativeKey, found.size());
            return false;
        }
        Attachment attachment = found.get(0);
        if (attachment.getTargetType() != PolymorphicTargetType.CLAIM) {
            log.debug("[ClaimAttachmentAuthz] 대상 유형 불일치 key={} attachmentId={} targetType={}", relativeKey,
                    attachment.getPublicId(), attachment.getTargetType());
            return false;
        }
        Long claimOwnerId = null;
        if (attachment.isLinked()) {
            Optional<Claim> claim = claimRepository.findById(attachment.getTargetId());
            if (claim.isEmpty()) {
                log.debug("[ClaimAttachmentAuthz] 대상 클레임 없음 key={} attachmentId={} claimId={}", relativeKey,
                        attachment.getPublicId(), attachment.getTargetId());
                return false;
            }
            claimOwnerId = claim.get().getRequestedBy();
        }
        for (String token : candidateTokens) {
            Optional<TokenPayload> payload = verifyQuietly(token);
            if (payload.isPresent() && isAllowed(attachment, claimOwnerId, payload.get())) {
                return true;
            }
        }
        log.debug("[ClaimAttachmentAuthz] 열람 거부 key={} attachmentId={} candidates={}", relativeKey,
                attachment.getPublicId(), candidateTokens.size());
        return false;
    }

    /** @param claimOwnerId 연결 첨부의 클레임 요청자(claim.requested_by). 미연결이면 null */
    private static boolean isAllowed(Attachment attachment, Long claimOwnerId, TokenPayload payload) {
        boolean buyer = payload.role() == ActorRole.BUYER;
        if (!attachment.isLinked()) {
            return buyer && payload.actorId().equals(attachment.getUploadedBy());
        }
        return (buyer && payload.actorId().equals(claimOwnerId)) || payload.role() == ActorRole.ADMIN;
    }

    /** 무효·만료 토큰은 해당 후보만 버린다(요청 실패 금지·토큰 값은 로그에 남기지 않는다). */
    private Optional<TokenPayload> verifyQuietly(String token) {
        try {
            TokenPayload payload = tokenProvider.verify(token);
            userStateVerifier.verify(payload); // 필터를 건너뛰는 경로라 삭제·탈퇴·갱신 이전 토큰 거부를 여기서도 적용(Track 84)
            return Optional.of(payload);
        } catch (AuthenticationException exception) {
            log.debug("[ClaimAttachmentAuthz] 후보 토큰 무효: {}", exception.getMessage());
            return Optional.empty();
        }
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
