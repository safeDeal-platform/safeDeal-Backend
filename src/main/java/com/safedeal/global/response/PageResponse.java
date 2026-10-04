package com.safedeal.global.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 오프셋 기반 페이징 응답 계약 — Spring의 {@code Page}/{@code Pageable}을 그대로 노출하면
 * 내부 구조가 API 계약이 되어버리므로 이 DTO로 감싼다.
 *
 * 페이지 크기: 기본 {@value #DEFAULT_SIZE}, 최대 {@value #MAX_SIZE}. 초과 요청은 조용히
 * 깎지 않고 400으로 거부한다(검증은 컨트롤러 담당). application.yml의
 * {@code max-page-size=100}은 안전망일 뿐 이 400 정책을 대신하지 못한다.
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
