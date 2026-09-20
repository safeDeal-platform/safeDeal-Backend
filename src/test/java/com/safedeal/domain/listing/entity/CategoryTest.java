package com.safedeal.domain.listing.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2단 구조 불변식과 비활성 전환을 고정한다. DB가 필요 없는 순수 단위 테스트라
 * IntegrationTestSupport를 상속하지 않는다.
 */
class CategoryTest {

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("대분류는 부모가 없고 depth 1, 생성 직후 활성이다")
        void root() {
            Category root = Category.root("DIGITAL", "디지털기기", 1);

            assertThat(root.getCode()).isEqualTo("DIGITAL");
            assertThat(root.getParent()).isNull();
            assertThat(root.parentCode()).isNull();
            assertThat(root.getDepth()).isEqualTo(Category.ROOT_DEPTH);
            assertThat(root.isLeaf()).isFalse();
            assertThat(root.isActive()).isTrue();
        }

        @Test
        @DisplayName("중분류는 대분류를 부모로 갖고 depth 2이며 leaf다")
        void child() {
            Category root = Category.root("DIGITAL", "디지털기기", 1);

            Category child = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);

            assertThat(child.getParent()).isSameAs(root);
            assertThat(child.parentCode()).isEqualTo("DIGITAL");
            assertThat(child.getDepth()).isEqualTo(Category.LEAF_DEPTH);
            assertThat(child.isLeaf()).isTrue();
        }

        @Test
        @DisplayName("중분류에 부모가 없으면 만들 수 없다")
        void childWithoutParent() {
            assertThatThrownBy(() -> Category.child("DIGITAL_PHONE", "스마트폰", null, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DIGITAL_PHONE");
        }

        @Test
        @DisplayName("중분류를 부모로 삼으면 3단이 되므로 막는다")
        void childOfChild() {
            Category root = Category.root("DIGITAL", "디지털기기", 1);
            Category child = Category.child("DIGITAL_PHONE", "스마트폰", root, 1);

            assertThatThrownBy(() -> Category.child("DIGITAL_PHONE_IPHONE", "아이폰", child, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("대분류여야");
        }

        @Test
        @DisplayName("중분류 code가 부모 code로 시작하지 않으면 막는다")
        void childCodeMustCarryParentPrefix() {
            Category digital = Category.root("DIGITAL", "디지털기기", 1);

            // code 규약 {대분류}_{중분류}은 "부모를 옮기면 code도 바뀐다"를 보장한다.
            // 이 검증이 없으면 부모만 조용히 옮겨져 정의와 어긋난 소속이 남는다.
            assertThatThrownBy(() -> Category.child("FASHION_TOP", "상의", digital, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("부모 code로 시작");
        }

        @Test
        @DisplayName("code나 name이 비면 만들 수 없다")
        void blankFields() {
            assertThatThrownBy(() -> Category.root("  ", "디지털기기", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("code");

            assertThatThrownBy(() -> Category.root("DIGITAL", "", 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name");
        }
    }

    @Nested
    @DisplayName("변경")
    class Modify {

        @Test
        @DisplayName("표시명과 정렬 순서는 바뀌어도 code는 그대로다")
        void renameKeepsCode() {
            Category root = Category.root("DIGITAL", "디지털기기", 1);

            root.rename("디지털·가전", 5);

            assertThat(root.getName()).isEqualTo("디지털·가전");
            assertThat(root.getSortOrder()).isEqualTo(5);
            assertThat(root.getCode()).isEqualTo("DIGITAL");
            assertThat(root.getDepth()).isEqualTo(Category.ROOT_DEPTH);
        }

        @Test
        @DisplayName("비활성으로 내렸다가 다시 올릴 수 있다")
        void deactivateAndActivate() {
            Category root = Category.root("DIGITAL", "디지털기기", 1);

            root.deactivate();
            assertThat(root.isActive()).isFalse();

            root.activate();
            assertThat(root.isActive()).isTrue();
        }
    }
}
