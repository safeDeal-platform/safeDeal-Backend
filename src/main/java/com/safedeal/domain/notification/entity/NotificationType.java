package com.safedeal.domain.notification.entity;

/**
 * 알림 종류. 정책('정책' 페이지 — 재민/알림)이 정의한 6종.
 *
 * 가격 변동 알림은 "찜한 매물의 인하" 또는 "최저가 갱신"에만 발생한다 — 인상 알림은 보내지 않는다.
 * (사용자가 원치 않는 알림으로 이탈하는 것을 막기 위한 의도적 정책)
 *
 * enum 이름은 Kafka 이벤트 계약이나 클라이언트 표시에 그대로 노출되므로(EnumType.STRING 저장),
 * 한 번 공개한 값은 함부로 바꾸지 않는다.
 */
public enum NotificationType {

    /** 가격 변동 — 찜한 매물 인하 · 최저가 갱신 시에만 (인상 알림 없음). */
    PRICE_DROP,

    /** 채팅 메시지 수신. */
    CHAT,

    /** 시세 챗봇 답장. */
    CHATBOT_REPLY,

    /** 판매 공정(거래 진행 상태 변화 — 배송/구매확정 등). */
    TRADE_PROCESS,

    /** 매물 검증 완료. */
    VERIFICATION_COMPLETE,

    /** 신고 처리 결과. */
    REPORT
}
