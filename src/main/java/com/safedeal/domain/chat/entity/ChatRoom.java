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
 * (매물, 구매자)당 1개인 채팅방. 대화가 분쟁 증거라 절대 지우지 않고, 새 방을 만들 수
 * 있는지는 매물 상태로만 판단한다 — 그래서 이 엔티티엔 상태 컬럼이 없다.
 *
 * <p>재진입 복구·읽음 커서 갱신은 전부 {@code ChatRoomRepository}의 조건부 UPDATE로 한다.
 * 값을 읽어와 setter로 고치면, 그 사이 다른 요청이 올려둔 읽음 커서를 예전 값으로 덮어써
 * 버린다 — 그래서 이 엔티티에는 상태를 바꾸는 메서드가 없다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "chat_rooms",
        uniqueConstraints = {
                @UniqueConstraint(name = ChatRoom.UK_PUBLIC_ID, columnNames = "public_id"),
                // 같은 매물에 같은 구매자가 겹쳐 눌러도 방은 하나만 생기게 막는 최종 방어선.
                @UniqueConstraint(name = ChatRoom.UK_BUYER_LISTING,
                        columnNames = {"buyer_id", "listing_id"})
        }
)
public class ChatRoom extends MutableEntity {

    /** 제약 이름 상수 — 재시도 판정과 애너테이션이 같은 문자열을 써야 한다(하나만 바뀌면 재시도가 조용히 멈춘다). */
    public static final String UK_PUBLIC_ID = "uk_chat_rooms_public_id";
    public static final String UK_BUYER_LISTING = "uk_chat_rooms_buyer_listing";

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

    /** 매물 조인 없이 판매자 id를 바로 쓰려는 비정규화(원본 컬럼이 안 바뀌어 어긋날 위험 없음). */
    @Column(name = "seller_id", nullable = false, updatable = false)
    private Long sellerId;

    /** 구매자가 방을 나가(숨겨) 목록에서 안 보이는 시각. null이면 보인다. */
    @Column(name = "buyer_hidden_at")
    private Instant buyerHiddenAt;

    @Column(name = "seller_hidden_at")
    private Instant sellerHiddenAt;

    /** 구매자가 마지막으로 읽은 메시지 id. 기본값 0 — null이면 "새값보다 작은지" 비교 자체가 안 돼 첫 갱신이 영영 실패한다. */
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

    /** 구매자인지. 메시지를 보낸 사람에 따라 어느 쪽 읽음 커서를 올릴지 가를 때 쓴다. */
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
