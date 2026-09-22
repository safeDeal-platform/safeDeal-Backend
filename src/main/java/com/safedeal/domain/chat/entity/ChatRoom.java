package com.safedeal.domain.chat.entity;

import com.safedeal.global.entity.MutableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * (매물, 구매자)당 1개인 채팅방. 정책(노션 "채팅 — 재민" · API 명세서 CHT-1)이 방을 절대
 * 지우지 않기로 했다 — 대화가 분쟁 증거이기 때문이다. 그래서 상태 컬럼이 없다: 판매 완료·
 * 삭제·제재로 매물이 어떻게 되든 방 자체는 그대로 남고, "지금 새 방을 만들 수 있는지"는
 * 매물({@code Listing}) 쪽 상태로만 판정한다.
 *
 * <p><b>{@code MutableEntity}를 상속하는 이유:</b> 재진입 복구({@link #rejoinAsBuyer()})가
 * 실제 UPDATE를 일으킨다 — {@code CreatedEntity}의 판정 기준("실제 UPDATE의 존재 여부",
 * {@code CreatedEntity} 클래스 주석)과 {@code MutableEntity} 클래스 주석이 채팅방의 커서
 * 갱신을 직접 예로 든 것 둘 다에 부합한다.
 *
 * <p><b>{@code sellerId}를 비정규화해 중복 저장하는 이유:</b> 알림 도메인의 {@code targetId}
 * 처럼 다른 도메인을 join 없이 자기완결적으로 응답하려는 것이다. {@code Listing.sellerId}는
 * {@code updatable = false}라 원본이 바뀌어 어긋날 위험이 없다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_rooms",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_chat_rooms_public_id", columnNames = "public_id"),
                // 동시 클릭의 최종 방어(API 명세서 CHT-1). 선두를 buyer_id로 둔 이유는 다음
                // 슬라이스의 "내 채팅방 목록(구매자)"이 이 인덱스를 그대로 타게 하기 위해서다
                // — 제약의 의미 자체는 컬럼 순서와 무관하다.
                @UniqueConstraint(name = "uk_chat_rooms_buyer_listing",
                        columnNames = {"buyer_id", "listing_id"})
        }
)
public class ChatRoom extends MutableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 외부 노출 식별자(ULID). 응답의 roomId가 이 값이다. */
    @Column(name = "public_id", nullable = false, length = 26, updatable = false)
    private String publicId;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private Long listingId;

    @Column(name = "buyer_id", nullable = false, updatable = false)
    private Long buyerId;

    /** 비정규화(클래스 주석 참고). */
    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;

    /**
     * 구매자가 방을 나가(숨겨) 목록에서 안 보이는 시각. null이면 보인다. 판매자 쪽
     * ({@code sellerHiddenAt})을 쓰는 경로는 이번 슬라이스에 없다 — 숨기기는 CHT-4, 판매자
     * 복구는 메시지 수신(CHT-2) 소유다. 컬럼만 먼저 두고 쓰는 코드는 나중 슬라이스가 채운다.
     */
    @Column(name = "buyer_hidden_at")
    private Instant buyerHiddenAt;

    @Column(name = "seller_hidden_at")
    private Instant sellerHiddenAt;

    /**
     * 이 방에서 구매자가 마지막으로 읽은 메시지 id(정책 "채팅 — 재민": 메시지별 is_read
     * 대신 방에 커서 2개). 기본값 0 — {@code null}로 두면 조건부 갱신의 {@code < 새값}
     * 비교가 SQL에서 UNKNOWN이 돼 첫 갱신이 영원히 실패한다(무엇과도 비교 불가). 0은
     * "존재할 수 없는 messageId"이자 "아직 아무것도 안 읽음"을 동시에 의미해 자연스럽다.
     */
    @Column(name = "buyer_last_read_message_id", nullable = false)
    private Long buyerLastReadMessageId = 0L;

    @Column(name = "seller_last_read_message_id", nullable = false)
    private Long sellerLastReadMessageId = 0L;

    private ChatRoom(String publicId, Long listingId, Long buyerId, Long sellerId) {
        this.publicId = publicId;
        this.listingId = listingId;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.buyerLastReadMessageId = 0L;
        this.sellerLastReadMessageId = 0L;
    }

    public static ChatRoom open(String publicId, Long listingId, Long buyerId, Long sellerId) {
        return new ChatRoom(publicId, listingId, buyerId, sellerId);
    }

    /**
     * 구매자 쪽 숨김을 되돌린다(API 명세서 CHT-1 "숨김 상태였다면 재진입 시 복구"). 이미
     * 보이는 상태면 그대로 둔다 — 몇 번을 호출해도 결과가 같은 멱등 연산이다
     * ({@code Notification.markAsRead}와 같은 형태).
     *
     * <p>판매자 쪽을 건드리지 않는 이유: 정책이 "메시지 수신 시 수신자 hidden_at=null"이라고
     * 당사자별로 규정했고, 이 엔드포인트의 행위자는 언제나 구매자다(본인 매물은 애초에
     * 막힌다).
     */
    public void rejoinAsBuyer() {
        this.buyerHiddenAt = null;
    }

    /**
     * 구매자·판매자 둘 중 하나인지(참여자 판정, {@code Listing.isOwnedBy}와 같은 형태).
     * 메시지 저장 시 "누가 보냈는지"로 어느 쪽 커서를 갱신할지 가르는 데 쓴다.
     */
    public boolean isBuyer(Long userId) {
        return buyerId.equals(userId);
    }

    public boolean isSeller(Long userId) {
        return sellerId.equals(userId);
    }

    public boolean isParticipant(Long userId) {
        return isBuyer(userId) || isSeller(userId);
    }
}
