package com.safedeal.domain.listing.repository;

import com.safedeal.domain.listing.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findByCode(String code);

    /**
     * 비활성 포함 전량. 응답에는 활성만 나가지만, 부모 code를 채우려면 비활성 부모도 필요하다
     * — 부모만 내려간 상태에서 활성만 읽으면 자식의 parentCode가 빈다.
     *
     * <p>수십 행짜리 마스터 테이블이라 전량 조회가 페이지네이션보다 싸다. 정렬을 DB에서 끝내
     * 서비스가 다시 정렬하지 않는다.
     */
    List<Category> findAllByOrderByDepthAscSortOrderAsc();
}
