package com.safedeal.domain.listing.seed;

import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 기동 시 카테고리 마스터를 {@link CategorySeedData} 정의에 맞춘다.
 *
 * <p>지우고 다시 넣지 않고 <b>code 기준 upsert</b>다. 매물·가격통계가 카테고리 id를 FK로
 * 참조하므로 행을 지웠다 다시 만들면 id가 바뀌어 참조가 끊긴다. 그래서 있으면 표시명·정렬만
 * 맞추고, 없으면 새로 만들고, 정의에서 빠진 것은 <b>삭제가 아니라 비활성</b>으로 내린다.
 *
 * <p>몇 번을 재기동해도 결과가 같아야 한다 — 로컬은 {@code create-drop}이라 매번 새로 돌고,
 * 운영은 배포마다 돈다. 여기서 부작용이 누적되면 환경마다 데이터가 달라진다.
 *
 * <p>대분류를 내릴 때는 그 아래 중분류도 함께 내린다. 부모만 사라지면 소속 없는 중분류가
 * 목록에 뜬다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategorySeeder implements ApplicationRunner {

    private final CategoryRepository categoryRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Map<String, Category> existing = new HashMap<>();
        for (Category category : categoryRepository.findAll()) {
            existing.put(category.getCode(), category);
        }

        Set<String> definedCodes = new HashSet<>();
        int created = 0;
        int reconciled = 0;

        int rootOrder = 1;
        for (CategorySeedData.RootSeed rootSeed : CategorySeedData.ROOTS) {
            requireNotDuplicated(definedCodes, rootSeed.code());
            Category root = existing.get(rootSeed.code());
            if (root == null) {
                root = categoryRepository.save(
                        Category.root(rootSeed.code(), rootSeed.name(), rootOrder));
                existing.put(root.getCode(), root);
                created++;
            } else {
                root.rename(rootSeed.name(), rootOrder);
                root.activate();
                reconciled++;
            }
            rootOrder++;

            int leafOrder = 1;
            for (CategorySeedData.LeafSeed leafSeed : rootSeed.leaves()) {
                requireNotDuplicated(definedCodes, leafSeed.code());
                Category leaf = existing.get(leafSeed.code());
                if (leaf == null) {
                    leaf = categoryRepository.save(
                            Category.child(leafSeed.code(), leafSeed.name(), root, leafOrder));
                    existing.put(leaf.getCode(), leaf);
                    created++;
                } else {
                    leaf.rename(leafSeed.name(), leafOrder);
                    leaf.activate();
                    reconciled++;
                }
                leafOrder++;
            }
        }

        int deactivated = deactivateRemoved(existing.values(), definedCodes);

        // "갱신"이라고 쓰면 매 기동마다 전건이 바뀐 것처럼 읽힌다. 실제로는 정의와 대조해
        // 표시명·순서를 맞춘 건수이고, 값이 같으면 아무것도 바뀌지 않는다.
        log.info("카테고리 시드 반영 - 신규 {}건, 대조 {}건, 비활성 {}건 (정의 {}건)",
                created, reconciled, deactivated, definedCodes.size());
    }

    /**
     * 같은 code가 정의에 두 번 들어가면 두 번째는 조용히 무시되고 첫 번째의 부모·순서가 남는다.
     * 복붙 실수를 기동 시점에 드러낸다 — 조용히 넘어가면 어느 정의가 반영됐는지 알 수 없다.
     */
    private void requireNotDuplicated(Set<String> definedCodes, String code) {
        if (!definedCodes.add(code)) {
            throw new IllegalStateException("카테고리 정의에 중복된 code가 있습니다: " + code);
        }
    }

    /**
     * 정의에서 빠진 분류를 내린다. 대분류가 빠지면 그 자식도 같이 내려간다 — 자식이 정의에
     * 남아 있더라도 부모 없이는 화면에 걸 자리가 없다.
     */
    private int deactivateRemoved(Iterable<Category> all, Set<String> definedCodes) {
        Set<String> removedRootCodes = new HashSet<>();
        for (Category category : all) {
            if (!category.isLeaf() && !definedCodes.contains(category.getCode())) {
                removedRootCodes.add(category.getCode());
            }
        }

        int deactivated = 0;
        for (Category category : all) {
            boolean removedItself = !definedCodes.contains(category.getCode());
            boolean parentRemoved = category.isLeaf()
                    && removedRootCodes.contains(category.parentCode());
            if ((removedItself || parentRemoved) && category.isActive()) {
                category.deactivate();
                deactivated++;
            }
        }
        return deactivated;
    }
}
