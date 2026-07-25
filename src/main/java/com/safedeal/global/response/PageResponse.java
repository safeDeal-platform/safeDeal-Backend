package com.safedeal.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 오프셋 기반 페이징 응답 계약.
 *
 * Spring의 {@code Page<T>}/{@code Pageable}을 API 응답으로 직접 노출하지 않는다 — 내부
 * 구현(Pageable, Sort 객체 구조 등)이 그대로 클라이언트 계약이 되어버리는 것을 막기 위해
 * 이 DTO로 한 번 감싼다.
 *
 * 페이지 크기 정책: 기본 {@value #DEFAULT_SIZE}, 최대 {@value #MAX_SIZE}.
 * 최대치를 넘는 size 요청은 조용히 clamp하지 말고 400(잘못된 요청)으로 거부한다 —
 * 클라이언트가 자신이 실제로 얼마를 요청했는지 알 수 있어야 페이지네이션 로직을 신뢰할 수 있다.
 * (실제 검증은 컨트롤러/도메인 담당자가 이 상수를 참조해 구현한다.)
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious
) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext(),
                page.hasPrevious()
        );
    }
}
