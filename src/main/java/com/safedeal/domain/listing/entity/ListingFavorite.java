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
 * 찜(관심 매물). 가격변동 알림의 데이터 소스다.
 *
 * <p>{@link #notifyBasePrice}가 이 테이블의 존재 이유다 — 찜한 시점의 가격을 기준가로 잡아야
 * 알림 도메인이 "이만큼 내렸나"를 판정할 수 있다(판정·발송은 알림 도메인 소관).
 *
 * <p>해제는 하드 삭제다. 본인 데이터이고 분쟁 증거 가치가 없다. 다시 찜하면 새 행이라
 * 그 시점 가격에서 새로 시작한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "listing_favorites",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_listing_favorites_user_listing",
                columnNames = {"user_id", "listing_id"}),
        indexes = {
                // 내 찜 목록: user_id로 걸러 최신순(created_at, id) 커서 페이징
                @Index(name = "idx_listing_favorites_user_created",
                        columnList = "user_id, created_at, id"),
                // 가격 인하 시 "이 매물을 찜한 사람들"을 찾는 경로(알림 도메인)
                @Index(name = "idx_listing_favorites_listing", columnList = "listing_id")
        })
public class ListingFavorite extends MutableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "listing_id", nullable = false, updatable = false)
    private Listing listing;

    /** 알림 기준가. 찜한 시점의 매물 가격이다. */
    @Column(name = "notify_base_price", nullable = false)
    private int notifyBasePrice;

    private ListingFavorite(Long userId, Listing listing, int notifyBasePrice) {
        this.userId = userId;
        this.listing = listing;
        this.notifyBasePrice = notifyBasePrice;
    }

    /**
     * 찜 행을 만든다. 실제 등록 경로는 동시성 때문에 네이티브 {@code insertIfAbsent}를 쓰므로,
     * 이 팩터리는 그 경로를 타지 않는 테스트 픽스처 전용이다.
     *
     * <p>기준가를 인자로 받는다 — "찜한 시점의 가격"이라는 규칙은 등록 경로
     * ({@code FavoriteCommandService})가 소유하며, 여기서 다시 읽으면 같은 규칙이 두 곳에 생긴다.
     *
     * <p>본인 매물도 찜할 수 있다(정책) — 막으면 판매자가 다른 계정으로 찜하는 우회만 생긴다.
     */
    public static ListingFavorite of(Long userId, Listing listing, int notifyBasePrice) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자는 필수입니다");
        }
        if (listing == null) {
            throw new IllegalArgumentException("매물은 필수입니다");
        }
        return new ListingFavorite(userId, listing, notifyBasePrice);
    }
}
