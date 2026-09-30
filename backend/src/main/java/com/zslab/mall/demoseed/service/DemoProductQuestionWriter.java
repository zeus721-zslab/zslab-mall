package com.zslab.mall.demoseed.service;

import com.zslab.mall.demoseed.repository.DemoSeedRepository;
import com.zslab.mall.demoseed.template.DemoProductQuestionTemplates.QuestionAnswer;
import com.zslab.mall.productquestion.entity.ProductQuestion;
import com.zslab.mall.productquestion.service.ProductQuestionService;
import com.zslab.mall.productquestion.service.SellerProductQuestionService;
import com.zslab.mall.user.entity.User;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 하나의 데모 질문·답변 적재(D-244). 이 메서드 하나가 상품 단위 트랜잭션이다 — 실패하면 그 상품의 질문·답변·시각 보정이 함께 롤백되고, 앞서
 * 끝난 상품은 각자 커밋돼 남는다. 쓰기는 도메인 서비스(질문 등록·셀러 답변)를 호출하고, 서비스 직접 호출로 빠지는 DTO 검증(질문 5~500자·
 * 답변 1~1000자)은 여기서 다시 확인한다.
 */
@Slf4j
@Service
public class DemoProductQuestionWriter {

    static final int QUESTION_MIN_LENGTH = 5;
    static final int QUESTION_MAX_LENGTH = 500;
    static final int ANSWER_MAX_LENGTH = 1000;
    /** 시각 난수를 미답변 목표 난수(상품 id 시드)와 분리하는 값. */
    private static final long TIMELINE_SEED_SALT = 0x5EED_0244L;

    private final ProductQuestionService productQuestionService;
    private final SellerProductQuestionService sellerProductQuestionService;
    private final DemoSeedRepository demoSeedRepository;

    public DemoProductQuestionWriter(ProductQuestionService productQuestionService,
            SellerProductQuestionService sellerProductQuestionService, DemoSeedRepository demoSeedRepository) {
        this.productQuestionService = productQuestionService;
        this.sellerProductQuestionService = sellerProductQuestionService;
        this.demoSeedRepository = demoSeedRepository;
    }

    /** 상품 하나의 적재 입력. */
    public record ProductTarget(Long productId, String productPublicId, LocalDateTime productCreatedAt, Long sellerId, Long ownerUserId,
            List<QuestionAnswer> templates, Set<String> existingContents, DemoQuestionQuota quota) {
    }

    /** 새 질문 한 건의 계획(작성자·시각·문구). */
    private record PlannedQuestion(User author, LocalDateTime askedAt, QuestionAnswer template) {
    }

    /**
     * 부족분만큼 질문을 만들고 오래된 순으로 addAnswered건에 셀러 답변을 단 뒤 시각을 보정한다. 가장 최근 질문들이 미답변으로 남는다.
     *
     * @throws IllegalStateException 쓸 수 있는 문구가 부족하거나 문구 길이가 서버 제약을 벗어날 때(상품 롤백)
     */
    @Transactional
    public void write(ProductTarget target, List<User> buyers, LocalDateTime now) {
        List<PlannedQuestion> planned = plan(target, buyers, now);
        Random answerDelays = new Random(target.productId() + TIMELINE_SEED_SALT);
        for (int index = 0; index < planned.size(); index++) {
            PlannedQuestion question = planned.get(index);
            ProductQuestion created = productQuestionService.create(question.author().getId(), target.productPublicId(),
                    question.template().question());
            LocalDateTime answeredAt = null;
            if (index < target.quota().addAnswered()) {
                sellerProductQuestionService.answer(target.sellerId(), target.ownerUserId(), created.getPublicId(),
                        question.template().answer());
                answeredAt = DemoQuestionTimeline.answerAt(question.askedAt(), now, answerDelays);
            }
            demoSeedRepository.shiftTimes(created.getId(), question.askedAt(), answeredAt == null ? question.askedAt() : answeredAt,
                    answeredAt);
        }
        log.info("[DemoSeed] 상품 질문 적재 productPublicId={} 질문 {} · 답변 {}", target.productPublicId(), planned.size(),
                target.quota().addAnswered());
    }

    /** 작성자는 상품 id부터 순환 배정하고, 시각은 상품·작성자 생성 이후로 흩은 뒤 오래된 순으로 정렬한다. */
    private List<PlannedQuestion> plan(ProductTarget target, List<User> buyers, LocalDateTime now) {
        List<QuestionAnswer> templates = pickTemplates(target);
        Random random = new Random(target.productId() ^ TIMELINE_SEED_SALT);
        List<PlannedQuestion> planned = new ArrayList<>();
        for (int index = 0; index < templates.size(); index++) {
            User author = buyers.get((int) ((target.productId() + index) % buyers.size()));
            LocalDateTime lowerBound = author.getCreatedAt().isAfter(target.productCreatedAt()) ? author.getCreatedAt()
                    : target.productCreatedAt();
            planned.add(new PlannedQuestion(author, DemoQuestionTimeline.questionAt(lowerBound, now, random), templates.get(index)));
        }
        planned.sort(Comparator.comparing(PlannedQuestion::askedAt));
        return planned;
    }

    /** 상품 id로 정한 시작점부터 돌며 상품에 이미 있는 문구를 건너뛴다(한 상품 안 중복 금지). */
    private List<QuestionAnswer> pickTemplates(ProductTarget target) {
        List<QuestionAnswer> all = target.templates();
        int start = new Random(target.productId()).nextInt(all.size());
        List<QuestionAnswer> picked = new ArrayList<>();
        for (int offset = 0; offset < all.size() && picked.size() < target.quota().total(); offset++) {
            QuestionAnswer candidate = all.get((start + offset) % all.size());
            if (!target.existingContents().contains(candidate.question())) {
                requireLengths(candidate);
                picked.add(candidate);
            }
        }
        if (picked.size() < target.quota().total()) {
            throw new IllegalStateException("쓸 수 있는 질문 문구가 부족합니다: 필요 " + target.quota().total() + " · 가능 " + picked.size());
        }
        return picked;
    }

    private static void requireLengths(QuestionAnswer candidate) {
        int questionLength = candidate.question().trim().length();
        int answerLength = candidate.answer().trim().length();
        if (questionLength < QUESTION_MIN_LENGTH || questionLength > QUESTION_MAX_LENGTH || answerLength == 0
                || answerLength > ANSWER_MAX_LENGTH) {
            throw new IllegalStateException("질문·답변 문구 길이가 서버 제약을 벗어났습니다: " + candidate.question());
        }
    }
}
