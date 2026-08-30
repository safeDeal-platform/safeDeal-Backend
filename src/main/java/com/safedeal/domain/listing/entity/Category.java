package com.safedeal.domain.listing.entity;

import com.safedeal.global.entity.MutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매물 분류 마스터. 대분류(depth 1) → 중분류(depth 2) 2단 고정이다.
 *
 * <p>enum이 아니라 테이블인 이유: 카테고리는 매물 필터·가격통계·챗봇이 공유하는 축이라 값이
 * 늘거나 표시명이 바뀔 때 배포 없이 반영돼야 한다. 대신 <b>모든 참조는 숫자 id가 아니라
 * {@link #code}로 한다</b> — 로컬 시드 id는 환경마다 달라서 id로 참조하면 즉시 깨진다.
 * 그래서 {@code code}는 한 번 정하면 바꾸지 않는 업무 식별자다(표시명 {@link #name}만 바뀐다).
 *
 * <p>매물은 반드시 <b>중분류(leaf)</b>를 가리킨다. 대분류에 직접 매물을 달면 가격통계 집계
 * 단위가 무너진다 — 검증은 서비스 계층이 한다.
 *
 * <p><b>물리 삭제 금지.</b> 이미 매물이 참조 중인 분류를 지우면 그 매물의 분류가 고아가 되고
 * 과거 가격통계의 의미도 사라진다. 목록에서 빠진 분류는 {@link #deactivate()}로 내린다.
 * 대분류를 내릴 때는 그 아래 중분류도 함께 내려야 한다 — 부모가 사라진 중분류만 남으면
 * 화면에 소속 없는 항목이 뜬다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_categories_code", columnNames = "code"),
        indexes = @Index(
                name = "idx_categories_parent_sort", columnList = "parent_id, sort_order"))
public class Category extends MutableEntity {

    /** 대분류 depth. */
    public static final int ROOT_DEPTH = 1;
    /** 중분류(leaf) depth — 매물이 가리킬 수 있는 유일한 단계. */
    public static final int LEAF_DEPTH = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    /**
     * 대분류면 null. LAZY지만 {@code getParent().getId()}는 프록시를 깨우지 않으므로
     * 목록 조회에서 부모 id를 읽는 것만으로는 추가 쿼리가 나가지 않는다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    private Category(String code, String name, Category parent, int depth, int sortOrder) {
        this.code = code;
        this.name = name;
        this.parent = parent;
        this.depth = depth;
        this.active = true;
        this.sortOrder = sortOrder;
    }

    /** 대분류를 만든다. */
    public static Category root(String code, String name, int sortOrder) {
        requireText(code, "code");
        requireText(name, "name");
        return new Category(code, name, null, ROOT_DEPTH, sortOrder);
    }

    /**
     * 중분류를 만든다. 부모는 반드시 대분류여야 한다 — 중분류 밑에 또 중분류를 달면 2단 고정이
     * 깨지고, 그 순간 "매물은 leaf를 가리킨다"는 규칙이 판정 불가가 된다.
     */
    public static Category child(String code, String name, Category parent, int sortOrder) {
        requireText(code, "code");
        requireText(name, "name");
        if (parent == null) {
            throw new IllegalArgumentException("중분류는 부모(대분류)가 있어야 합니다: " + code);
        }
        if (parent.depth != ROOT_DEPTH) {
            throw new IllegalArgumentException(
                    "중분류의 부모는 대분류여야 합니다: " + code + " -> " + parent.code);
        }
        return new Category(code, name, parent, LEAF_DEPTH, sortOrder);
    }

    /** 매물이 가리킬 수 있는 단계인지. */
    public boolean isLeaf() {
        return depth == LEAF_DEPTH;
    }

    /** 부모 code. 대분류면 null. */
    public String parentCode() {
        return parent == null ? null : parent.getCode();
    }

    /** 표시명·정렬 순서를 시드 정의에 맞춘다. code와 depth는 바꾸지 않는다. */
    public void rename(String name, int sortOrder) {
        requireText(name, "name");
        this.name = name;
        this.sortOrder = sortOrder;
    }

    public void activate() {
        this.active = true;
    }

    /** 목록에서 빠진 분류를 내린다. 삭제가 아니다 — 참조 중인 매물·통계가 있다. */
    public void deactivate() {
        this.active = false;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다");
        }
    }
}
