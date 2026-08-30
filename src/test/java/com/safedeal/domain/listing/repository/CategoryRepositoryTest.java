package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * code UNIQUE와 정렬을 실제 MySQL에서 확인한다. UNIQUE는 스키마 제약이라 메모리 테스트로는
 * 증명되지 않는다.
 *
 * <p>각 테스트는 {@code @Transactional}로 롤백된다 — 여기서는 테스트 스레드가 곧 트랜잭션
 * 주체라 롤백이 통한다. (시더 테스트는 사정이 달라 롤백을 쓰지 않는다.)
 */
@Transactional
class CategoryRepositoryTest extends IntegrationTestSupport {

    @Autowired
    CategoryRepository categoryRepository;

    /**
     * CategorySeeder가 기동 시 정의된 분류를 전부 넣어둔다. 이 테스트는 자기가 만든 행만
     * 보이는 상태를 전제로 하므로 먼저 비운다. {@code @Transactional}이라 롤백되어 다른
     * 테스트에는 영향이 없다.
     *
     * <p>자식이 부모를 FK로 참조하므로 중분류를 먼저 지운다 — 한 번에 지우면 순서에 따라
     * 외래키 위반이 난다.
     */
    @BeforeEach
    void clearSeeded() {
        List<Category> all = categoryRepository.findAll();
        categoryRepository.deleteAll(all.stream().filter(Category::isLeaf).toList());
        categoryRepository.flush();
        categoryRepository.deleteAll(all.stream().filter(c -> !c.isLeaf()).toList());
        categoryRepository.flush();
    }

    @Test
    @DisplayName("같은 code를 두 번 저장할 수 없다")
    void codeIsUnique() {
        categoryRepository.saveAndFlush(Category.root("DIGITAL", "디지털기기", 1));

        assertThatThrownBy(() ->
                categoryRepository.saveAndFlush(Category.root("DIGITAL", "이름만 다름", 2)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("code로 찾는다")
    void findByCode() {
        categoryRepository.saveAndFlush(Category.root("DIGITAL", "디지털기기", 1));

        assertThat(categoryRepository.findByCode("DIGITAL"))
                .isPresent()
                .get()
                .extracting(Category::getName)
                .isEqualTo("디지털기기");
        assertThat(categoryRepository.findByCode("NOPE")).isEmpty();
    }

    @Test
    @DisplayName("대분류가 먼저, 그 안에서 sortOrder 순으로 나온다")
    void sortedByDepthThenSortOrder() {
        Category digital = categoryRepository.save(Category.root("DIGITAL", "디지털기기", 2));
        Category fashion = categoryRepository.save(Category.root("FASHION", "의류·잡화", 1));
        categoryRepository.save(Category.child("DIGITAL_TABLET", "태블릿", digital, 2));
        categoryRepository.save(Category.child("DIGITAL_PHONE", "스마트폰", digital, 1));
        categoryRepository.save(Category.child("FASHION_MEN", "남성의류", fashion, 1));
        categoryRepository.flush();

        List<String> codes = categoryRepository.findAllByOrderByDepthAscSortOrderAsc()
                .stream().map(Category::getCode).toList();

        assertThat(codes).containsExactly(
                "FASHION", "DIGITAL",
                "DIGITAL_PHONE", "FASHION_MEN", "DIGITAL_TABLET");
    }

    @Test
    @DisplayName("비활성으로 내려도 행은 남는다")
    void deactivateKeepsRow() {
        Category saved = categoryRepository.saveAndFlush(Category.root("ETC", "기타", 99));

        saved.deactivate();
        categoryRepository.flush();

        assertThat(categoryRepository.findByCode("ETC"))
                .isPresent()
                .get()
                .extracting(Category::isActive)
                .isEqualTo(false);
    }
}
