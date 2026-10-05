package com.safedeal.domain.listing.dto;

import com.safedeal.domain.listing.entity.Category;

import java.util.List;

/**
 * 카테고리 목록 응답. 트리가 아니라 평면 리스트로 내린다 — 2뎁스 고정이라 클라이언트가
 * parentCode로 조립하는 비용이 거의 없다. 외부 식별자는 숫자 id가 아니라 code다 — 시드 id는
 * 환경마다 달라 로컬에서 되던 요청이 운영에서 깨진다.
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
