package com.zslab.mall.demoseed.template;

import java.util.List;
import java.util.Map;

/**
 * 데모 상품 Q&A 문구(D-244 · 템플릿 SoT는 Java). 카테고리 표시명별 질문·답변 쌍이며, 목록에 없는 카테고리는 {@link #FALLBACK_CATEGORY} 세트를 쓴다.
 * 서비스 직접 호출은 DTO 검증을 거치지 않으므로 질문 5~500자·답변 1~1000자·광고·연락처·링크 없음을 이 목록에서 지킨다
 * (DemoProductQuestionTemplatesTest가 고정).
 */
public final class DemoProductQuestionTemplates {

    /** 질문·답변 한 쌍. */
    public record QuestionAnswer(String question, String answer) {
    }

    /** 목록에 없는 카테고리가 쓰는 세트(데모 카테고리는 여러 종류 상품이 섞여 있어 일반 문구로 둔다). */
    public static final String FALLBACK_CATEGORY = "데모";

    private static final Map<String, List<QuestionAnswer>> BY_CATEGORY = Map.of(
            "리빙·주방", List.of(
                    new QuestionAnswer("식기세척기에 넣어도 괜찮을까요?", "식기세척기 사용이 가능합니다. 다만 오래 쓰시려면 부드러운 수세미로 손세척하시는 것을 권해 드려요."),
                    new QuestionAnswer("인덕션에서도 사용할 수 있나요?", "바닥이 인덕션 겸용이라 가스레인지와 인덕션 모두 사용하실 수 있습니다."),
                    new QuestionAnswer("처음 받으면 따로 세척해야 하나요?", "받으신 뒤 미지근한 물과 중성세제로 한 번 세척하고 물기를 말린 뒤 사용해 주세요."),
                    new QuestionAnswer("전자레인지에 돌려도 되나요?", "뚜껑을 제외한 본체는 전자레인지 사용이 가능합니다. 뚜껑은 열을 받으면 변형될 수 있어요."),
                    new QuestionAnswer("냄새가 배지 않는 소재인가요?", "냄새와 색이 잘 배지 않는 소재입니다. 사용 후 바로 세척하시면 더 깔끔하게 쓰실 수 있어요."),
                    new QuestionAnswer("무게가 대략 어느 정도인가요?", "포장을 제외한 본체 무게는 약 1.2kg입니다. 한 손으로 들기에 부담 없는 정도예요."),
                    new QuestionAnswer("선물 포장도 가능한가요?", "별도 선물 포장은 제공하지 않지만 제품 상자가 깔끔해 그대로 선물하셔도 좋습니다."),
                    new QuestionAnswer("사용하다 코팅이 벗겨지면 교환되나요?", "정상 사용 중 1년 안에 코팅이 벗겨지면 사진과 함께 문의 주시면 확인 후 교환해 드립니다."),
                    new QuestionAnswer("오븐에 넣어도 괜찮을까요?", "손잡이 부분 때문에 오븐 사용은 권하지 않습니다. 레인지 위에서만 사용해 주세요."),
                    new QuestionAnswer("구성품이 어떻게 되나요?", "본체와 뚜껑, 사용 설명서가 함께 들어 있습니다."),
                    new QuestionAnswer("세척 후 물때가 생기면 어떻게 관리하나요?", "식초를 조금 푼 물로 헹군 뒤 마른 행주로 닦아 주시면 물때가 잘 지워집니다."),
                    new QuestionAnswer("주문하면 보통 언제 출고되나요?", "평일 오후 2시 전 결제 건은 당일 출고되고, 이후 결제 건은 다음 영업일에 출고됩니다.")),
            "의류", List.of(
                    new QuestionAnswer("평소 95 사이즈를 입는데 어떤 사이즈가 맞을까요?", "정사이즈로 나와서 평소 95를 입으시면 M 사이즈를 추천드려요."),
                    new QuestionAnswer("세탁기에 돌려도 줄어들지 않나요?", "찬물 단독 세탁을 권장드립니다. 건조기를 쓰시면 조금 줄어들 수 있어 자연 건조를 추천드려요."),
                    new QuestionAnswer("비침이 있는 편인가요?", "밝은 색상은 약간 비침이 있을 수 있어 안에 이너를 받쳐 입으시는 것을 권해 드립니다."),
                    new QuestionAnswer("모델 착용 사이즈가 궁금합니다.", "모델은 키 178cm에 L 사이즈를 착용했습니다."),
                    new QuestionAnswer("신축성이 좋은 편인가요?", "스판이 약간 들어가 있어 움직이실 때 편안한 정도의 신축성이 있습니다."),
                    new QuestionAnswer("보풀이 잘 생기는 소재인가요?", "보풀이 적게 생기는 원단을 사용했습니다. 뒤집어서 세탁망에 넣어 세탁하시면 더 오래 입으실 수 있어요."),
                    new QuestionAnswer("색상이 사진과 비슷한가요?", "자연광에서 촬영해 실제 색상과 거의 같습니다. 모니터 설정에 따라 조금 달라 보일 수 있어요."),
                    new QuestionAnswer("다림질이 필요한 소재인가요?", "구김이 적은 편이라 세탁 후 털어서 말리시면 따로 다림질하지 않으셔도 됩니다."),
                    new QuestionAnswer("사이즈 교환은 어떻게 하나요?", "받으신 날부터 7일 안에 착용 흔적 없이 반품 신청해 주시면 원하시는 사이즈로 교환해 드립니다."),
                    new QuestionAnswer("사계절 내내 입을 수 있을까요?", "봄가을에 가장 잘 맞고, 여름에는 조금 덥게 느끼실 수 있습니다."),
                    new QuestionAnswer("총장이 어느 정도인가요?", "M 사이즈 기준 총장은 약 70cm로 엉덩이를 살짝 덮는 길이입니다."),
                    new QuestionAnswer("세탁 후 색 빠짐이 있나요?", "처음 한두 번은 약간 이염이 있을 수 있어 단독 세탁을 권해 드립니다.")),
            "잡화", List.of(
                    new QuestionAnswer("카드가 몇 장 정도 들어가나요?", "카드 슬롯에 한 칸당 두 장씩, 모두 여섯 장 정도 넉넉하게 들어갑니다."),
                    new QuestionAnswer("가죽 냄새가 심한 편인가요?", "처음에는 가죽 특유의 냄새가 조금 있지만 통풍이 잘되는 곳에 두시면 며칠 안에 빠집니다."),
                    new QuestionAnswer("비 오는 날 사용해도 괜찮을까요?", "생활 방수 정도는 되지만 오래 젖으면 변색될 수 있어 마른 천으로 바로 닦아 주세요."),
                    new QuestionAnswer("각인 서비스가 가능한가요?", "현재는 각인 서비스를 제공하지 않고 있습니다. 양해 부탁드립니다."),
                    new QuestionAnswer("사이즈 조절이 되나요?", "뒤쪽 조절 끈으로 머리 둘레 54cm에서 60cm까지 맞추실 수 있습니다."),
                    new QuestionAnswer("노트북도 들어가는 크기인가요?", "13인치 노트북까지는 여유 있게 들어가고, 15인치는 조금 빠듯합니다."),
                    new QuestionAnswer("세탁은 어떻게 하나요?", "찬물에 중성세제로 가볍게 손세탁한 뒤 그늘에서 말려 주세요."),
                    new QuestionAnswer("선물용 상자에 담겨 오나요?", "제품 전용 상자에 담겨 배송되어 따로 포장하지 않고 선물하셔도 좋습니다."),
                    new QuestionAnswer("무게가 가벼운 편인가요?", "약 200g으로 가벼운 편이라 오래 들고 다니셔도 부담이 적습니다."),
                    new QuestionAnswer("안쪽에 수납 공간이 따로 있나요?", "안쪽에 지퍼 주머니 하나와 오픈 포켓 두 개가 있어 소지품을 나눠 담기 좋습니다."),
                    new QuestionAnswer("오래 쓰면 모양이 무너지지 않나요?", "심지를 덧대어 제작해 오래 사용하셔도 모양이 잘 유지됩니다."),
                    new QuestionAnswer("색상이 여러 가지인가요?", "현재 블랙과 실버 두 가지 색상으로 판매하고 있습니다.")),
            "디지털", List.of(
                    new QuestionAnswer("아이폰과 안드로이드 모두 호환되나요?", "블루투스 5.3을 지원해 아이폰과 안드로이드 모두 문제없이 연결됩니다."),
                    new QuestionAnswer("완전히 충전하는 데 얼마나 걸리나요?", "지원 충전기로 약 2시간이면 완충되고, 완충 후 최대 8시간 사용하실 수 있습니다."),
                    new QuestionAnswer("보증 기간이 어떻게 되나요?", "구매일로부터 1년 동안 무상 수리를 받으실 수 있습니다."),
                    new QuestionAnswer("노트북 충전도 가능한가요?", "USB-C PD를 지원하는 노트북이라면 충전하실 수 있습니다. 노트북 권장 출력도 함께 확인해 주세요."),
                    new QuestionAnswer("소음이 큰 편인가요?", "조용한 사무실에서도 거슬리지 않을 만큼 작동음이 작습니다."),
                    new QuestionAnswer("여러 기기에 동시에 연결할 수 있나요?", "최대 두 대까지 동시에 연결하고 버튼으로 전환해서 쓰실 수 있습니다."),
                    new QuestionAnswer("구성품에 케이블이 들어 있나요?", "USB-C 케이블 1개가 함께 들어 있습니다. 충전 어댑터는 따로 준비해 주세요."),
                    new QuestionAnswer("펌웨어 업데이트는 어떻게 하나요?", "전용 앱을 설치하시면 새 펌웨어가 나왔을 때 알림을 받고 바로 업데이트하실 수 있습니다."),
                    new QuestionAnswer("비행기에 들고 탈 수 있나요?", "용량이 100Wh 이하라 기내 반입이 가능합니다. 위탁 수하물로는 부칠 수 없어요."),
                    new QuestionAnswer("배터리 잔량을 확인할 수 있나요?", "본체의 표시등으로 잔량을 네 단계로 확인하실 수 있습니다."),
                    new QuestionAnswer("초기 불량이면 교환되나요?", "받으신 날부터 7일 안에 불량이 확인되면 새 제품으로 교환해 드립니다."),
                    new QuestionAnswer("윈도우와 맥 모두 쓸 수 있나요?", "별도 드라이버 설치 없이 윈도우와 맥 모두 바로 인식됩니다.")),
            "문구", List.of(
                    new QuestionAnswer("잉크가 번지지 않나요?", "빨리 마르는 잉크라 일반 노트에서는 번짐이 거의 없습니다."),
                    new QuestionAnswer("리필 심을 따로 구매할 수 있나요?", "리필 심은 곧 별도 상품으로 판매할 예정입니다. 조금만 기다려 주세요."),
                    new QuestionAnswer("종이 두께가 어느 정도인가요?", "100g 종이를 사용해 만년필로 써도 뒷면에 거의 비치지 않습니다."),
                    new QuestionAnswer("몇 매로 구성되어 있나요?", "한 권당 80매, 160쪽으로 구성되어 있습니다."),
                    new QuestionAnswer("아이들이 써도 안전한 소재인가요?", "안전 기준 검사를 통과한 소재로 만들어 아이들도 안심하고 쓸 수 있습니다."),
                    new QuestionAnswer("책상 위에서 미끄러지지 않나요?", "바닥면에 미끄럼 방지 처리가 되어 있어 쓰는 동안 잘 밀리지 않습니다."),
                    new QuestionAnswer("색상 구성이 어떻게 되나요?", "기본 색상 여섯 가지와 파스텔 색상 여섯 가지, 모두 열두 가지로 구성되어 있습니다."),
                    new QuestionAnswer("속지가 줄 노트인가요, 무지인가요?", "7mm 줄 노트로 되어 있습니다. 무지 버전은 현재 준비 중이에요."),
                    new QuestionAnswer("선물 포장이 되나요?", "선물 포장은 따로 제공하지 않지만 케이스가 있어 그대로 선물하시기 좋습니다."),
                    new QuestionAnswer("오래 보관해도 잉크가 마르지 않나요?", "뚜껑을 잘 닫아 두시면 몇 달 보관해도 잉크가 마르지 않습니다."),
                    new QuestionAnswer("크기가 어느 정도인가요?", "A5 크기라 가방에 넣어 다니기 좋습니다."),
                    new QuestionAnswer("대량 구매 할인이 있나요?", "현재는 수량별 할인을 따로 진행하지 않고 있습니다.")),
            "데모", List.of(
                    new QuestionAnswer("주문하면 보통 며칠 안에 받을 수 있나요?", "평일 기준 결제 다음 날 출고되고, 출고 후 보통 1~2일 안에 받아보실 수 있습니다."),
                    new QuestionAnswer("받은 뒤 마음에 안 들면 반품할 수 있나요?", "받으신 날부터 7일 안에 사용 흔적이 없으면 반품 신청하실 수 있습니다."),
                    new QuestionAnswer("재입고 예정이 있나요?", "재고가 떨어지면 보통 2주 안에 다시 입고됩니다."),
                    new QuestionAnswer("실물 색상이 사진과 같나요?", "실물 색상과 최대한 가깝게 촬영했지만 화면 설정에 따라 조금 달라 보일 수 있습니다."),
                    new QuestionAnswer("선물 포장도 가능한가요?", "별도 선물 포장은 제공하지 않지만 제품 상자가 깔끔해 그대로 선물하셔도 좋습니다."),
                    new QuestionAnswer("구성품이 어떻게 되나요?", "본품과 간단한 사용 안내서가 함께 들어 있습니다."),
                    new QuestionAnswer("관리 방법이 따로 있을까요?", "직사광선과 습기를 피해 보관하시면 오래 사용하실 수 있습니다."),
                    new QuestionAnswer("크기가 어느 정도인가요?", "손바닥보다 조금 큰 크기라 책상이나 선반 어디에 두어도 잘 어울립니다."),
                    new QuestionAnswer("불량이면 교환되나요?", "받으신 날부터 7일 안에 불량이 확인되면 새 제품으로 교환해 드립니다."),
                    new QuestionAnswer("여러 개 주문하면 한 번에 오나요?", "같은 판매자의 상품은 한 상자에 담아 한 번에 보내 드립니다."),
                    new QuestionAnswer("향이 강한 편인가요?", "은은한 편이라 작은 방에 두셔도 부담스럽지 않습니다."),
                    new QuestionAnswer("무게가 무거운 편인가요?", "300g 정도로 가벼운 편이라 옮기기 편합니다.")));

    private DemoProductQuestionTemplates() {
    }

    /** 카테고리 표시명의 질문·답변 쌍. 목록에 없거나 카테고리 행이 없는(null) 경우 대체 세트를 돌려준다. */
    public static List<QuestionAnswer> forCategory(String categoryDisplayName) {
        List<QuestionAnswer> pairs = categoryDisplayName == null ? null : BY_CATEGORY.get(categoryDisplayName);
        return pairs == null ? BY_CATEGORY.get(FALLBACK_CATEGORY) : pairs;
    }

    /** 전체 세트(테스트 고정용). */
    public static Map<String, List<QuestionAnswer>> all() {
        return BY_CATEGORY;
    }
}
