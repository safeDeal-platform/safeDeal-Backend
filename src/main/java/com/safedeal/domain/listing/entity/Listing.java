package com.safedeal.domain.listing.entity;

import com.safedeal.global.entity.MutableEntity;
import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 판매 매물.
 *
 * <p><b>seller_id에 FK를 걸지 않는다</b> — users는 인증 도메인 소유인데 아직 엔티티가 없다.
 * 여기서 먼저 정의하면 남의 도메인 스키마를 선점하게 된다. V1 마이그레이션 작성 시 FK를 건다.
 *
 * <p><b>가격은 DTO 검증과 여기 양쪽에서 막는다.</b> DTO만 믿으면 이벤트 수신·배치처럼 컨트롤러를
 * 안 거치는 경로로 0원·음수가 들어온다. 그런 값 하나가 가격 통계 전체를 오염시킨다.
 *
 * <p><b>조회수는 이 엔티티로 올리지 않는다.</b> 더티체킹으로 올리면 {@code @Version}이 증가해
 * "조회만 했는데 판매자의 수정이 실패하는" 버그가 난다. 별도 벌크 UPDATE로 처리한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "listings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_listings_public_id", columnNames = "public_id"),
        // 정책이 DTO 검증과 DB 제약의 병행을 요구한다. 컨트롤러를 안 거치는 경로(이벤트
        // 수신·배치)로 들어온 값 하나가 가격 통계 전체를 오염시키기 때문이다.
        // Hibernate의 @Check는 7에서 deprecated라 Jakarta Persistence 3.2 표준을 쓴다.
        check = @CheckConstraint(
                name = "ck_listings_price",
                constraint = "price BETWEEN 1000 AND 100000000"),
        indexes = {
                // 목록 조회의 기본 정렬(created_at DESC, id DESC)과 노출 조건을 함께 태운다.
                @Index(name = "idx_listings_status_created",
                        columnList = "status, created_at, id"),
                @Index(name = "idx_listings_category_status_created",
                        columnList = "category_id, status, created_at, id"),
                @Index(name = "idx_listings_seller", columnList = "seller_id, status")
        })
public class Listing extends MutableEntity {

    public static final int MIN_PRICE = 1_000;
    public static final int MAX_PRICE = 100_000_000;
    public static final int MAX_TITLE_LENGTH = 100;
    /** 하루 인하 허용 횟수. 반복해서 내렸다 올려 노출을 끌어올리는 어뷰징을 막는다. */
    public static final int MAX_PRICE_DROPS_PER_DAY = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출 식별자(ULID). API 경로·응답은 내부 id가 아니라 이 값을 쓴다. */
    @Column(name = "public_id", nullable = false, length = 26, updatable = false)
    private String publicId;

    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Lob
    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private int price;

    /** 반드시 중분류(leaf). 대분류에 매물이 달리면 가격통계 집계 단위가 무너진다. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ItemCondition itemCondition;

    @Column(name = "region_sido", nullable = false, length = 20)
    private String regionSido;

    @Column(name = "region_sigungu", nullable = false, length = 20)
    private String regionSigungu;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ListingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "sold_source", length = 10)
    private SoldSource soldSource;

    @Column(name = "sold_at")
    private Instant soldAt;

    /** 인하 횟수를 세는 기준일. 날짜가 바뀌면 카운트를 리셋한다. */
    @Column(name = "price_drop_date")
    private LocalDate priceDropDate;

    @Column(name = "price_drop_count", nullable = false)
    private int priceDropCount;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    /**
     * 이미지 구성이 바뀔 때마다 올린다. 검증 완료 처리는 "요청 시점의 image_version과 같을 때만"
     * 반영해야, 검증 도중 이미지를 갈아끼워 낡은 승인을 붙이는 우회를 막을 수 있다.
     */
    @Column(name = "image_version", nullable = false)
    private long imageVersion;

    /** 내용 수정 충돌용. 상태 전이는 이것이 아니라 조건부 UPDATE로 막는다 — 서로 다른 문제다. */
    @Version
    private Long version;

    /** 소프트 삭제 시각. 물리 삭제하면 거래 기록이 고아가 되고 사기 후 증거 인멸이 가능해진다. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    private Listing(String publicId, Long sellerId, String title, String description, int price,
                    Category category, ItemCondition itemCondition,
                    String regionSido, String regionSigungu, ListingStatus status) {
        this.publicId = publicId;
        this.sellerId = sellerId;
        this.title = title;
        this.description = description;
        this.price = price;
        this.category = category;
        this.itemCondition = itemCondition;
        this.regionSido = regionSido;
        this.regionSigungu = regionSigungu;
        this.status = status;
        this.priceDropCount = 0;
        this.viewCount = 0;
        this.imageVersion = 1L;
    }

    /**
     * 매물을 등록한다.
     *
     * @param verificationEnabled 검증 구간을 켤지. 꺼져 있으면 DRAFT를 거치지 않고 바로 공개된다
     *                            — 검증 도메인이 나중에 붙기 때문에, 그때 등록 코드를 다시 쓰지
     *                            않으려고 상태를 하드코딩하지 않는다.
     */
    public static Listing register(String publicId, Long sellerId, String title, String description,
                                   int price, Category category, ItemCondition itemCondition,
                                   String regionSido, String regionSigungu,
                                   boolean verificationEnabled) {
        requireText(publicId, "publicId");
        if (sellerId == null) {
            throw new IllegalArgumentException("판매자는 필수입니다");
        }
        requireText(title, "title");
        requireText(description, "description");
        requireText(regionSido, "regionSido");
        requireText(regionSigungu, "regionSigungu");
        if (itemCondition == null) {
            throw new IllegalArgumentException("물품 상태는 필수입니다");
        }
        validatePrice(price);
        requireLeafCategory(category);

        ListingStatus initial = verificationEnabled
                ? ListingStatus.PENDING_VERIFICATION
                : ListingStatus.ACTIVE;
        return new Listing(publicId, sellerId, title.strip(), description, price, category,
                itemCondition, regionSido.strip(), regionSigungu.strip(), initial);
    }

    public boolean isOwnedBy(Long userId) {
        return sellerId.equals(userId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** 목록에 실어도 되는지. */
    public boolean isListable() {
        return !isDeleted() && status.isListable();
    }

    /** 상세로 열어줘도 되는지. 목록과 기준이 다르다 — 팔린 매물의 상세는 열려 있어야 한다. */
    public boolean isViewable() {
        return !isDeleted() && status.isViewable();
    }

    private static void validatePrice(int price) {
        if (price < MIN_PRICE || price > MAX_PRICE) {
            throw new IllegalArgumentException(
                    "가격은 %,d원 이상 %,d원 이하여야 합니다: %d".formatted(MIN_PRICE, MAX_PRICE, price));
        }
    }

    private static void requireLeafCategory(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("카테고리는 필수입니다");
        }
        if (!category.isLeaf()) {
            throw new IllegalArgumentException(
                    "매물은 중분류에만 등록할 수 있습니다: " + category.getCode());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다");
        }
    }
}
