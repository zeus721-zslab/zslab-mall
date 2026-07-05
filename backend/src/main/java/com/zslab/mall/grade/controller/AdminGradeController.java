package com.zslab.mall.grade.controller;

import com.zslab.mall.grade.controller.response.GradeRecalculationResponse;
import com.zslab.mall.grade.service.GradeRecalculationBatchService;
import com.zslab.mall.grade.service.GradeRecalculationResult;
import com.zslab.mall.grade.service.GradeService;
import com.zslab.mall.user.entity.User;
import com.zslab.mall.user.exception.UserNotFoundException;
import com.zslab.mall.user.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin 액터용 등급 재산정 REST 컨트롤러(Track 51 Phase 3). 클래스 base path 없이 메서드 절대경로를 부여한다
 * ({@link com.zslab.mall.settlement.controller.AdminSettlementController} 선례). 인가는 SecurityConfig의
 * {@code /api/v1/admin/**}→{@code hasRole("ADMIN")}가 강제하므로 메서드 @PreAuthorize를 두지 않는다.
 *
 * <p>HTTP 책임만 가진다: 위임·public_id 해소·HTTP 변환. 산정·순회·부분성공은 Service 책임이다.
 */
@RestController
public class AdminGradeController {

    private final GradeRecalculationBatchService gradeRecalculationBatchService;
    private final GradeService gradeService;
    private final UserRepository userRepository;

    public AdminGradeController(GradeRecalculationBatchService gradeRecalculationBatchService,
            GradeService gradeService, UserRepository userRepository) {
        this.gradeRecalculationBatchService = gradeRecalculationBatchService;
        this.gradeService = gradeService;
        this.userRepository = userRepository;
    }

    /**
     * 전체 buyer 등급 재산정 배치(β 주경로). 성공 200 + 처리 요약(총·성공·실패). 개별 buyer 실패는 배치를 중단하지 않는다
     * ({@link GradeRecalculationBatchService} 부분 성공).
     */
    @PostMapping("/api/v1/admin/grades/recalculate")
    public ResponseEntity<GradeRecalculationResponse> recalculateAll() {
        GradeRecalculationResult result = gradeRecalculationBatchService.recalculateAll();
        return ResponseEntity.ok(GradeRecalculationResponse.from(result));
    }

    /**
     * 단일 buyer 등급 재산정(α). publicId(usr_)를 User.id로 해소해 위임한다(AdminClaimController public_id 해소 선례).
     * 성공 204(No Content). 미존재 publicId 404({@link UserNotFoundException})·활성 정책 미매칭 500·lock 기간은 무변경 후 204.
     */
    @PostMapping("/api/v1/admin/buyers/{publicId}/grade/recalculate")
    public ResponseEntity<Void> recalculateOne(@PathVariable String publicId) {
        User user = userRepository.findByPublicId(publicId)
                .orElseThrow(() -> new UserNotFoundException("buyer를 찾을 수 없습니다: publicId=" + publicId));
        gradeService.recalculate(user.getId());
        return ResponseEntity.noContent().build();
    }
}
