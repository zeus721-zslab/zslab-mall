package com.zslab.mall.demoseed.template;

import java.util.List;
import java.util.Map;

/**
 * 데모 리뷰 본문(D-245 · 템플릿 SoT는 Java). 카테고리 표시명 × 별점대(높음 4~5 · 보통 3 · 낮음 1~2) 문장이며 {@value #NAME_TOKEN}은 상품명으로
 * 치환한다. 목록에 없는 카테고리는 {@link #FALLBACK_CATEGORY} 세트를 쓴다. 서비스 직접 호출은 DTO 검증을 거치지 않으므로 본문 1~1000자·광고·
 * 연락처·링크 없음을 이 목록에서 지킨다(DemoReviewPlanningTest가 고정).
 */
public final class DemoReviewTemplates {

    public static final String NAME_TOKEN = "{name}";
    public static final String FALLBACK_CATEGORY = "데모";

    /** 별점대. */
    public enum Band {
        HIGH, MIDDLE, LOW;

        public static Band of(int rating) {
            if (rating >= 4) {
                return HIGH;
            }
            return rating == 3 ? MIDDLE : LOW;
        }
    }

    private static final Map<String, Map<Band, List<String>>> BY_CATEGORY = Map.of(
            "리빙·주방", Map.of(
                    Band.HIGH, List.of(
                            "{name} 받자마자 써봤는데 마감이 깔끔하고 튼튼해요. 주방에 두니 분위기도 살아요.",
                            "생각보다 묵직하고 만듦새가 좋아요. {name} 덕분에 요리할 맛이 납니다.",
                            "세척이 정말 쉬워서 만족해요. {name} 재구매 의사 있어요.",
                            "가족들이 다 좋아해요. {name} 크기도 딱 적당하고 수납도 편합니다."),
                    Band.MIDDLE, List.of(
                            "{name} 쓸 만한데 기대보다 조금 가벼운 느낌이에요.",
                            "가격 생각하면 무난해요. {name} 세척은 편한 편입니다.",
                            "{name} 기본은 하는데 특별히 좋은 점은 모르겠어요."),
                    Band.LOW, List.of(
                            "{name} 모서리 마감이 거칠어서 조금 아쉬웠어요.",
                            "사진보다 크기가 작아서 쓰임새가 애매하네요. {name} 아쉬워요.",
                            "{name} 처음 쓸 때 냄새가 오래 남아서 불편했어요.")),
            "의류", Map.of(
                    Band.HIGH, List.of(
                            "{name} 핏이 예쁘고 소재가 부드러워요. 매일 입게 되네요.",
                            "정사이즈로 샀는데 딱 맞아요. {name} 색감도 사진이랑 같아요.",
                            "세탁 후에도 늘어나지 않아서 좋아요. {name} 다른 색도 살 생각이에요.",
                            "{name} 두께가 적당해서 요즘 날씨에 입기 좋아요."),
                    Band.MIDDLE, List.of(
                            "{name} 괜찮은데 생각보다 기장이 조금 길어요.",
                            "무난하게 입기 좋아요. {name} 다만 보풀이 조금 생기네요.",
                            "{name} 가격 대비 괜찮지만 소재가 약간 얇아요."),
                    Band.LOW, List.of(
                            "{name} 사이즈가 작게 나와서 한 치수 크게 사야 할 것 같아요.",
                            "세탁 한 번에 조금 줄었어요. {name} 관리가 까다롭네요.",
                            "{name} 색이 화면보다 어둡게 와서 아쉬웠어요.")),
            "잡화", Map.of(
                    Band.HIGH, List.of(
                            "{name} 마감이 깔끔하고 실용적이에요. 매일 들고 다녀요.",
                            "선물용으로 샀는데 받는 사람이 아주 좋아했어요. {name} 추천합니다.",
                            "{name} 수납 공간이 넉넉해서 편하게 쓰고 있어요.",
                            "가볍고 튼튼해요. {name} 오래 쓸 수 있을 것 같아요."),
                    Band.MIDDLE, List.of(
                            "{name} 무난하게 쓰기 좋은데 색이 조금 달라요.",
                            "가격 생각하면 괜찮아요. {name} 크기가 조금 작아요.",
                            "{name} 쓸 만하지만 특별히 눈에 띄는 점은 없어요."),
                    Band.LOW, List.of(
                            "{name} 박음질이 한쪽이 조금 풀려 있었어요.",
                            "생각보다 수납이 적어서 아쉬워요. {name} 기대보다 불편했어요.",
                            "{name} 냄새가 꽤 오래 남아서 한동안 못 썼어요.")),
            "디지털", Map.of(
                    Band.HIGH, List.of(
                            "{name} 연결이 빠르고 설정이 정말 쉬워요.",
                            "성능이 설명 그대로예요. {name} 배터리도 오래 가서 만족합니다.",
                            "{name} 소음이 적어서 밤에 써도 부담 없어요.",
                            "만듦새가 단단하고 마감이 좋아요. {name} 잘 샀다고 생각해요."),
                    Band.MIDDLE, List.of(
                            "{name} 기능은 괜찮은데 설명서가 조금 불친절해요.",
                            "쓸 만해요. {name} 다만 충전 시간이 생각보다 길어요.",
                            "{name} 가격 대비 무난한 성능이에요."),
                    Band.LOW, List.of(
                            "{name} 연결이 가끔 끊겨서 다시 연결해야 해요.",
                            "배터리가 설명보다 빨리 닳는 느낌이에요. {name} 아쉽네요.",
                            "{name} 버튼 반응이 느려서 쓰기 불편했어요.")),
            "문구", Map.of(
                    Band.HIGH, List.of(
                            "{name} 필기감이 부드럽고 번짐이 거의 없어요.",
                            "종이가 두꺼워서 만년필로 써도 비치지 않아요. {name} 추천해요.",
                            "{name} 구성이 알차서 선물로도 좋았어요.",
                            "색감이 예쁘고 오래 써도 질리지 않아요. {name} 또 살 거예요."),
                    Band.MIDDLE, List.of(
                            "{name} 괜찮은데 생각보다 크기가 작아요.",
                            "무난하게 쓰기 좋아요. {name} 잉크가 조금 늦게 말라요.",
                            "{name} 가격 생각하면 쓸 만한 정도예요."),
                    Band.LOW, List.of(
                            "{name} 잉크가 고르게 나오지 않아 조금 불편했어요.",
                            "제본이 약해서 몇 장이 떨어졌어요. {name} 아쉬워요.",
                            "{name} 색이 사진과 달라서 실망했어요.")),
            "데모", Map.of(
                    Band.HIGH, List.of(
                            "{name} 받아보니 사진보다 훨씬 예뻐요. 아주 만족합니다.",
                            "포장이 꼼꼼하고 배송도 빨랐어요. {name} 추천해요.",
                            "{name} 품질이 기대 이상이에요. 선물용으로 또 살게요.",
                            "가격 대비 정말 괜찮아요. {name} 잘 쓰고 있어요."),
                    Band.MIDDLE, List.of(
                            "{name} 무난해요. 딱 가격만큼의 품질이에요.",
                            "나쁘지 않은데 특별히 좋지도 않아요. {name} 그냥 평범해요.",
                            "{name} 괜찮지만 생각보다 크기가 작아요."),
                    Band.LOW, List.of(
                            "{name} 기대와 달라서 조금 실망했어요.",
                            "마감이 아쉬웠어요. {name} 다음에는 다른 걸 살 것 같아요.",
                            "{name} 사진과 색감이 달라서 아쉬워요.")));

    private DemoReviewTemplates() {
    }

    /** 카테고리·별점대 문장(상품명 치환 전). 목록에 없거나 null인 카테고리는 대체 세트를 쓴다. */
    public static List<String> forCategory(String categoryDisplayName, Band band) {
        Map<Band, List<String>> bands = categoryDisplayName == null ? null : BY_CATEGORY.get(categoryDisplayName);
        return (bands == null ? BY_CATEGORY.get(FALLBACK_CATEGORY) : bands).get(band);
    }

    public static String render(String template, String productName) {
        return template.replace(NAME_TOKEN, productName);
    }

    /** 전체 세트(테스트 고정용). */
    public static Map<String, Map<Band, List<String>>> all() {
        return BY_CATEGORY;
    }
}
