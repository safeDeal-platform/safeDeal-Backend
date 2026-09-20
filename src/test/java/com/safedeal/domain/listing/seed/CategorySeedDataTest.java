package com.safedeal.domain.listing.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드 정의 자체의 불변식을 고정한다. DB가 필요 없는 정적 데이터 검증이므로 컨테이너를 띄우지
 * 않는다 — 정의를 고치는 사람이 몇 초 안에 결과를 본다.
 */
class CategorySeedDataTest {

    private List<String> allCodes() {
        List<String> codes = new ArrayList<>();
        for (CategorySeedData.RootSeed root : CategorySeedData.ROOTS) {
            codes.add(root.code());
            root.leaves().forEach(leaf -> codes.add(leaf.code()));
        }
        return codes;
    }

    @Test
    @DisplayName("code는 전역에서 유일하다")
    void codesAreUnique() {
        List<String> codes = allCodes();

        assertThat(codes).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("중분류 code는 부모 code로 시작한다")
    void leafCodeCarriesParentPrefix() {
        for (CategorySeedData.RootSeed root : CategorySeedData.ROOTS) {
            assertThat(root.leaves())
                    .allSatisfy(leaf -> assertThat(leaf.code())
                            .startsWith(root.code() + "_"));
        }
    }

    @Test
    @DisplayName("모든 대분류는 중분류를 하나 이상 갖는다")
    void everyRootHasLeaf() {
        // 매물은 중분류에만 달리므로, 자식 없는 대분류는 고를 수는 있는데 등록은 못 하는
        // 막다른 길이 된다.
        assertThat(CategorySeedData.ROOTS)
                .allSatisfy(root -> assertThat(root.leaves()).isNotEmpty());
    }

    @Test
    @DisplayName("표시명이 비어 있지 않다")
    void namesArePresent() {
        for (CategorySeedData.RootSeed root : CategorySeedData.ROOTS) {
            assertThat(root.name()).isNotBlank();
            assertThat(root.leaves()).allSatisfy(leaf -> assertThat(leaf.name()).isNotBlank());
        }
    }
}
