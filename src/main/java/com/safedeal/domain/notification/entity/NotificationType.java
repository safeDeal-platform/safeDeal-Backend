package com.safedeal.domain.notification.entity;

/** 알림 종류(6종). enum 이름이 Kafka 이벤트·클라이언트 화면에 그대로 노출되므로, 한 번 공개한 값은 바꾸지 않는다. */
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
