package com.safedeal.domain.listing.seed;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시드 재실행 안전성을 고정한다. 로컬은 재기동마다, 운영은 배포마다 이 코드가 돌기 때문에
 * 여기서 부작용이 누적되면 환경마다 데이터가 달라진다.
 *
 * <p><b>{@code @Transactional}을 쓰지 않는다.</b> 시더는 {@code ApplicationRunner}라 자기
 * 트랜잭션에서 커밋한다. 테스트를 롤백 트랜잭션으로 감싸면 두 번째 실행이 첫 번째 결과를 보지
 * 못해 "두 번 돌려도 같은가"라는 검증 자체가 성립하지 않는다. 대신 {@code @AfterEach}에서
 * 직접 지운다.
 */
class CategorySeederTest extends IntegrationTestSupport {

    @Autowired
    CategorySeeder categorySeeder;

    @Autowired
    CategoryRepository categoryRepository;

    private static final int ROOT_COUNT = CategorySeedData.ROOTS.size();
    private static final int LEAF_COUNT =
            CategorySeedData.ROOTS.stream().mapToInt(r -> r.leaves().size()).sum();
    private static final int TOTAL = ROOT_COUNT + LEAF_COUNT;

    /**
     * 이 클래스는 {@code @Transactional}을 쓰지 않아 변경이 실제로 커밋된다. MySQL 컨테이너는
     * 전체 테스트가 공유하므로, 비운 채로 끝내면 뒤에 도는 클래스가 카테고리가 없는 DB를 만난다
     * (실행 순서에 따라 통과·실패가 갈린다). 그래서 지우는 대신 <b>정의 상태로 되돌린다.</b>
     */
    @AfterEach
    void restoreSeed() {
        clear();
        categorySeeder.run(null);
    }

    /** 자식이 부모를 FK로 참조하므로 중분류를 먼저 지운다. 한 번에 지우면 외래키 위반이 난다. */
    private void clear() {
        List<Category> all = categoryRepository.findAll();
        categoryRepository.deleteAll(all.stream().filter(Category::isLeaf).toList());
        categoryRepository.flush();
        categoryRepository.deleteAll(all.stream().filter(c -> !c.isLeaf()).toList());
        categoryRepository.flush();
    }

    @Test
    @DisplayName("정의한 대분류와 중분류를 모두 만든다")
    void seedsEverything() {
        clear();

        categorySeeder.run(null);

        List<Category> all = categoryRepository.findAll();
        assertThat(all).hasSize(TOTAL);
        assertThat(all).allMatch(Category::isActive);
        assertThat(all.stream().filter(c -> !c.isLeaf())).hasSize(ROOT_COUNT);
        assertThat(all.stream().filter(Category::isLeaf)).hasSize(LEAF_COUNT);
        assertThat(all.stream().filter(Category::isLeaf))
                .allSatisfy(leaf -> assertThat(leaf.parentCode()).isNotNull());
    }

    @Test
    @DisplayName("두 번 돌려도 행 수와 id가 그대로다")
    void isIdempotent() {
        clear();
        categorySeeder.run(null);
        Map<String, Long> idsBefore = categoryRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Category::getCode, Category::getId));

        categorySeeder.run(null);

        List<Category> after = categoryRepository.findAll();
        assertThat(after).hasSize(TOTAL);
        // id가 바뀌면 매물·가격통계의 FK 참조가 끊긴다 — 지우고 다시 넣지 않는다는 것의 증거.
        assertThat(after.stream()
                .collect(java.util.stream.Collectors.toMap(Category::getCode, Category::getId)))
                .isEqualTo(idsBefore);
    }

    @Test
    @DisplayName("정의에 없는 분류는 삭제하지 않고 비활성으로 내린다")
    void deactivatesInsteadOfDeleting() {
        clear();
        Category orphan = categoryRepository.saveAndFlush(
                Category.root("LEGACY_ONLY", "예전 분류", 99));
        Long orphanId = orphan.getId();

        categorySeeder.run(null);

        Category found = categoryRepository.findByCode("LEGACY_ONLY").orElseThrow();
        assertThat(found.getId()).isEqualTo(orphanId);
        assertThat(found.isActive()).isFalse();
        assertThat(categoryRepository.findAll()).hasSize(TOTAL + 1);
    }

    @Test
    @DisplayName("표시명이 바뀌면 code는 그대로 두고 이름만 갱신한다")
    void updatesDisplayNameKeepingCode() {
        clear();
        categorySeeder.run(null);
        Category digital = categoryRepository.findByCode("DIGITAL").orElseThrow();
        Long idBefore = digital.getId();
        digital.rename("옛 이름", 99);
        categoryRepository.flush();

        categorySeeder.run(null);

        Category reloaded = categoryRepository.findByCode("DIGITAL").orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(idBefore);
        assertThat(reloaded.getName()).isEqualTo(
                CategorySeedData.ROOTS.stream()
                        .filter(r -> r.code().equals("DIGITAL"))
                        .map(CategorySeedData.RootSeed::name)
                        .findFirst().orElseThrow());
    }

    @Test
    @DisplayName("비활성으로 내려간 분류가 정의에 다시 들어오면 되살아난다")
    void reactivates() {
        clear();
        categorySeeder.run(null);
        Category leaf = categoryRepository.findByCode("ETC_OTHER").orElseThrow();
        leaf.deactivate();
        categoryRepository.flush();

        categorySeeder.run(null);

        assertThat(categoryRepository.findByCode("ETC_OTHER").orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("대분류가 정의에서 빠지면 그 아래 중분류도 함께 비활성된다")
    void removingRootDeactivatesItsLeaves() {
        clear();
        // 정의에 없는 대분류와 그 자식을 직접 만들어 둔다.
        Category orphanRoot = categoryRepository.saveAndFlush(
                Category.root("LEGACY", "없어질 대분류", 99));
        categoryRepository.saveAndFlush(
                Category.child("LEGACY_ONE", "그 아래", orphanRoot, 1));

        categorySeeder.run(null);

        Category root = categoryRepository.findByCode("LEGACY").orElseThrow();
        Category leaf = categoryRepository.findByCode("LEGACY_ONE").orElseThrow();
        assertThat(root.isActive()).isFalse();
        // 부모만 내려가고 자식이 남으면 소속 없는 중분류가 목록에 뜬다.
        assertThat(leaf.isActive()).isFalse();
    }

}
