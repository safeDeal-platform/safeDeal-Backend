package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Category;

import java.util.List;

/**
 * 카테고리 목록 응답. 트리로 중첩하지 않고 평면 리스트로 내린다 — 2뎁스 고정이라 클라이언트가
 * parentCode로 조립하는 비용이 사실상 없고, "중분류만 필요한 화면"이 매번 평탄화를 하지 않아도
 * 된다.
 *
 * <p><b>숫자 id를 내리지 않는다.</b> 시드 id는 환경마다 달라서, 클라이언트가 id를 잡으면
 * 로컬에서 되던 게 운영에서 깨진다. 외부 식별자는 언제나 code다.
 */
public record CategoryResponse(List<Item> categories) {

    public record Item(String code, String name, String parentCode, int depth, int sortOrder) {
    }

    public static CategoryResponse from(List<Category> categories) {
        return new CategoryResponse(categories.stream()
                .map(c -> new Item(
                        c.getCode(), c.getName(), c.parentCode(), c.getDepth(), c.getSortOrder()))
                .toList());
    }
}
