package com.safedeal.domain.listing.seed;

import java.util.List;

/**
 * 카테고리 마스터의 정의 원본. 대분류 12 / 중분류 52.
 *
 * <p>DB가 아니라 코드가 원본인 이유: 로컬은 {@code create-drop}이라 재기동마다 테이블이 비고,
 * 카테고리가 비면 매물 등록이 전부 실패한다. {@code data.sql}은 로컬에서만 돌고 운영
 * ({@code validate} + Flyway)에서는 돌지 않아 두 환경이 갈라진다. 코드에 두고 기동 시
 * upsert하면 어느 환경에서든 같은 결과가 된다.
 *
 * <p><b>code는 바꾸지 않는다.</b> 매물·가격통계가 이 값으로 분류를 참조하므로, code를 바꾸면
 * 과거 데이터의 분류가 끊긴다. 표시명({@code name})만 바꾼다. 더 이상 쓰지 않을 분류는
 * 목록에서 지우면 되고, 시더가 행을 삭제하지 않고 비활성으로 내린다.
 *
 * <p>중분류가 곧 가격통계 집계 단위다. 잘게 쪼갤수록 시세가 정확해지지만, MVP는 여기까지다
 * — 같은 중분류 안의 기종 차이(예: 최신 스마트폰 vs 구형)는 상품명 기반 매칭이 들어가는
 * MVP 이후에 해소한다.
 */
public final class CategorySeedData {

    private CategorySeedData() {
    }

    /** 대분류 하나와 그에 속한 중분류들. */
    public record RootSeed(String code, String name, List<LeafSeed> leaves) {
    }

    /** 중분류. code는 {@code {대분류}_{중분류}} 형식이라 전역에서 겹치지 않는다. */
    public record LeafSeed(String code, String name) {
    }

    private static RootSeed root(String code, String name, LeafSeed... leaves) {
        return new RootSeed(code, name, List.of(leaves));
    }

    private static LeafSeed leaf(String code, String name) {
        return new LeafSeed(code, name);
    }

    public static final List<RootSeed> ROOTS = List.of(
            root("DIGITAL", "디지털기기",
                    leaf("DIGITAL_PHONE", "스마트폰"),
                    leaf("DIGITAL_TABLET", "태블릿"),
                    leaf("DIGITAL_LAPTOP", "노트북"),
                    leaf("DIGITAL_PC", "데스크탑·모니터"),
                    leaf("DIGITAL_CAMERA", "카메라"),
                    leaf("DIGITAL_AUDIO", "이어폰·헤드폰·스피커"),
                    leaf("DIGITAL_WEARABLE", "스마트워치"),
                    leaf("DIGITAL_ACC", "주변기기")),

            root("APPLIANCE", "생활가전",
                    leaf("APPLIANCE_KITCHEN", "주방가전"),
                    leaf("APPLIANCE_CLEAN", "청소기·세탁기"),
                    leaf("APPLIANCE_SEASON", "계절가전"),
                    leaf("APPLIANCE_TV", "TV·영상가전"),
                    leaf("APPLIANCE_BEAUTY", "이미용가전")),

            root("FURNITURE", "가구·인테리어",
                    leaf("FURNITURE_BED", "침대·매트리스"),
                    leaf("FURNITURE_SOFA", "소파·의자"),
                    leaf("FURNITURE_DESK", "책상·테이블"),
                    leaf("FURNITURE_STORAGE", "수납·선반"),
                    leaf("FURNITURE_DECO", "조명·소품")),

            root("LIVING", "생활·주방",
                    // 주방'가전'(전자레인지 등)은 APPLIANCE_KITCHEN이다. 여기는 그릇·냄비 같은
                    // 비전자 용품이다. 정리·수납은 FURNITURE_STORAGE와 겹쳐 두지 않는다.
                    leaf("LIVING_KITCHEN", "주방용품"),
                    leaf("LIVING_DAILY", "생활잡화"),
                    leaf("LIVING_BATH", "욕실·청소"),
                    leaf("LIVING_TOOL", "공구·자재")),

            root("FASHION", "의류·잡화",
                    leaf("FASHION_MEN", "남성의류"),
                    leaf("FASHION_WOMEN", "여성의류"),
                    leaf("FASHION_SHOES", "신발"),
                    leaf("FASHION_BAG", "가방·지갑"),
                    leaf("FASHION_WATCH", "시계·주얼리"),
                    leaf("FASHION_ACC", "패션잡화")),

            root("BEAUTY", "뷰티·미용",
                    leaf("BEAUTY_SKINCARE", "스킨케어"),
                    leaf("BEAUTY_MAKEUP", "메이크업"),
                    leaf("BEAUTY_HAIR", "헤어·바디"),
                    leaf("BEAUTY_PERFUME", "향수")),

            root("SPORTS", "스포츠·레저",
                    leaf("SPORTS_FITNESS", "헬스·요가"),
                    leaf("SPORTS_BIKE", "자전거"),
                    leaf("SPORTS_GOLF", "골프"),
                    leaf("SPORTS_CAMPING", "캠핑·등산"),
                    leaf("SPORTS_BALL", "구기·라켓")),

            root("HOBBY", "취미·수집",
                    leaf("HOBBY_GAME", "게임기·타이틀"),
                    leaf("HOBBY_MUSIC", "악기"),
                    leaf("HOBBY_ALBUM", "음반·굿즈"),
                    leaf("HOBBY_FIGURE", "피규어·프라모델"),
                    leaf("HOBBY_ART", "미술·수집품")),

            root("BOOK", "도서·티켓",
                    leaf("BOOK_GENERAL", "도서"),
                    leaf("BOOK_TEXT", "교재·참고서"),
                    leaf("BOOK_TICKET", "티켓·쿠폰")),

            root("KIDS", "유아동",
                    leaf("KIDS_CLOTHING", "유아동의류"),
                    leaf("KIDS_TOY", "완구·교구"),
                    leaf("KIDS_GEAR", "유모차·카시트"),
                    leaf("KIDS_FURNITURE", "유아가구")),

            root("PET", "반려동물",
                    leaf("PET_FOOD", "사료·간식"),
                    leaf("PET_SUPPLY", "용품")),

            root("ETC", "기타",
                    leaf("ETC_OTHER", "기타")));
}
