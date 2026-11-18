package com.safedeal.domain.listing.controller;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hibernate가 실제로 내보내는 SQL을 그대로 모은다 (LD-44: 손으로 쓴 SQL이 아니라 구현이 낸 찜 수 쿼리를 EXPLAIN).
 * Hibernate가 클래스 이름으로 만들어 쓰므로 public·인자 없는 생성자여야 하고, 기록은 static에 둔다.
 * {@link ListingDetailApiTest}의 컨텍스트에서만 켜진다(@TestPropertySource).
 */
public class RecordingStatementInspector implements StatementInspector {

    private static final List<String> SQLS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        SQLS.add(sql);
        return sql;
    }

    static void clear() {
        SQLS.clear();
    }

    static List<String> recorded() {
        return List.copyOf(SQLS);
    }
}
