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

import java.time.Instant;

/**
 * 찜(관심 매물). 가격변동 알림의 데이터 소스다.
 *
 * <p><b>테이블명은 {@code listing_favorites}</b> — 정책과 요구사항 명세서가 이 이름을 쓴다.
 * ERD만 {@code FAVORITES}로 적혀 있고 거기 주석에도 "테이블명 통일 필요(중현)"라고 남아 있어
 * 이쪽으로 맞춘다. 단순 {@code favorites}는 나중에 다른 대상(판매자 찜 등)이 생기면 무엇을
 * 찜한 것인지 이름만으로 알 수 없다.
 *
 * <p><b>{@link #notifyBasePrice}가 이 테이블의 존재 이유다.</b> 알림은 "찜한 시점보다 싸졌나"가
 * 아니라 "지금까지의 최저가보다 싸졌나"로 판정한다. 그래서 기준가를 찜한 시점 가격에서 시작해
 * 더 낮은 값이 나올 때만 낮추고, 판매자가 가격을 올려도 되돌리지 않는다 — 그래야 내렸다 올렸다를
 * 반복해도 알림이 반복 발송되지 않는다.
 *
 * <p>{@code MutableEntity}인 이유: 기준가와 마지막 발송 시각이 갱신된다. ERD에는 created_at만
 * 있으나, 정책의 판정 기준은 "테이블 이름이 아니라 실제 UPDATE 존재 여부"다.
 *
 * <p>해제는 하드 삭제다. 본인 데이터이고 분쟁 증거 가치가 없어 남길 이유가 없다. 다시 찜하면
 * 그 시점 가격으로 기준가가 새로 시작한다.
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

    /** 알림 기준가. 찜한 시점 가격에서 시작해 최저가로만 내려간다. */
    @Column(name = "notify_base_price", nullable = false)
    private int notifyBasePrice;

    /** 마지막 가격변동 알림 발송 시각. 아직 보낸 적 없으면 null. */
    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    private ListingFavorite(Long userId, Listing listing, int notifyBasePrice) {
        this.userId = userId;
        this.listing = listing;
        this.notifyBasePrice = notifyBasePrice;
    }

    /**
     * 찜한다. 기준가는 찜하는 순간의 가격이다.
     *
     * <p>본인 매물도 찜할 수 있다(정책) — 자기 매물의 가격 변동을 지켜보는 것을 막을 이유가 없고,
     * 막으면 판매자가 다른 계정으로 찜하는 우회만 만든다.
     */
    public static ListingFavorite of(Long userId, Listing listing) {
        if (userId == null) {
            throw new IllegalArgumentException("사용자는 필수입니다");
        }
        if (listing == null) {
            throw new IllegalArgumentException("매물은 필수입니다");
        }
        return new ListingFavorite(userId, listing, listing.getPrice());
    }

    /**
     * 알림을 보낼 만큼 내렸는지 판정하고, 그렇다면 기준가를 그 가격으로 내린다.
     *
     * <p>올랐거나 그대로면 아무것도 하지 않는다 — 기준가를 따라 올리면 "내렸다 올렸다"를
     * 반복하는 것만으로 알림이 계속 나간다.
     *
     * <p><b>발송 기록({@link #markNotified})은 여기서 찍지 않는다.</b> 실제 발송은 알림
     * 도메인에서 일어나고 실패할 수 있는데, 판정과 함께 미리 찍어두면 발송이 실패해도
     * 기준가는 이미 내려가 있어 <b>같은 가격으로는 두 번 다시 알림이 나가지 않는다.</b>
     * 최저가 기준 정책상 되돌릴 방법도 없으므로, 발송에 성공한 뒤에 따로 기록한다.
     *
     * @return 기준가가 실제로 내려갔으면 true(= 알림 대상)
     */
    public boolean lowerBasePriceIfDropped(int currentPrice) {
        if (currentPrice >= notifyBasePrice) {
            return false;
        }
        this.notifyBasePrice = currentPrice;
        return true;
    }

    /** 알림을 실제로 보낸 뒤에 호출한다. 발송 실패와 발송 성공을 구분하기 위해 분리했다. */
    public void markNotified(Instant now) {
        this.lastNotifiedAt = now;
    }
}
