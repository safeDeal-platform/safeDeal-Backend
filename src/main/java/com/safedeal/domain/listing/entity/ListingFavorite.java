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
 * <p><b>테이블명은 {@code listing_favorites}</b> — 정책과 요구사항 명세서가 이 이름을 쓴다.
 * ERD만 {@code FAVORITES}로 적혀 있고 거기 주석에도 "테이블명 통일 필요(중현)"라고 남아 있어
 * 이쪽으로 맞춘다. 단순 {@code favorites}는 나중에 다른 대상(판매자 찜 등)이 생기면 무엇을
 * 찜한 것인지 이름만으로 알 수 없다.
 *
 * <p><b>{@link #notifyBasePrice}가 이 테이블의 존재 이유다.</b> 명세대로 <b>찜한 시점의
 * 가격</b>을 기준가로 잡는다. 이 값이 찜 행에 붙어 있어야 알림 도메인이 "이 사용자에게 알릴
 * 만큼 내렸나"를 판정할 수 있다. 판정과 발송은 알림 도메인 소관이라 여기서 다루지 않는다.
 *
 * <p>{@code MutableEntity}인 이유: 기준가가 갱신될 수 있다. ERD에는 created_at만 있으나,
 * 정책의 판정 기준은 "테이블 이름이 아니라 실제 UPDATE 존재 여부"다.
 *
 * <p>해제는 하드 삭제다. 본인 데이터이고 분쟁 증거 가치가 없어 남길 이유가 없다. 기준가는
 * 행 단위이므로 다시 찜하면 그 시점 가격에서 새로 시작한다.
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
     * 찜 행을 만든다.
     *
     * <p><b>기준가를 인자로 받는다.</b> "기준가 = 찜한 시점의 가격"이라는 규칙은 등록 경로가
     * 소유한다({@code FavoriteCommandService}). 여기서 {@code listing.getPrice()}를 다시 읽으면
     * 같은 규칙이 두 곳에 생겨, 한쪽만 고쳐도 다른 쪽이 조용히 남는다.
     *
     * <p>운영 등록은 동시성 때문에 네이티브 {@code insertIfAbsent}로만 들어간다. 이 팩터리는
     * 그 경로를 타지 않는 곳(테스트 픽스처)에서 행을 만들 때 쓴다.
     *
     * <p>본인 매물도 찜할 수 있다(정책) — 자기 매물의 가격 변동을 지켜보는 것을 막을 이유가 없고,
     * 막으면 판매자가 다른 계정으로 찜하는 우회만 만든다.
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
