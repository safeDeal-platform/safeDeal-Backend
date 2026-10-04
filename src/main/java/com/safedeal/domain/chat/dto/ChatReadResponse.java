package com.safedeal.domain.chat.dto;

/**
 * 읽음 처리 결과(API 명세서 CHT-3). 항상 서버가 실제로 가진 커서를 돌려준다. 커서를
 * 전진시켰으면 그 값(=요청값)을 돌려준다. 전진하지 못했으면(역행 요청이거나, 다른 기기가
 * 먼저 더 큰 값으로 올렸으면) 그 시점 DB의 최신 커밋 값을 잠금 읽기로 다시 읽어 돌려준다.
 * 그래서 클라이언트에 실제보다 뒤처진 값을 말하지 않는다.
 */
public record ChatReadResponse(Long lastReadMessageId) {
}
