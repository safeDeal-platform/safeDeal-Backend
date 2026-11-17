package com.safedeal.testsupport;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 테스트가 정하고 움직일 수 있는 시계(QC-3). 기본값은 {@link #DEFAULT}(한국 시각 10:00)에 멈춰 있다.
 *
 * <p>{@link RecordingMailSender}와 같은 방식으로 통합 테스트 컨텍스트에 올린다 — 테스트 소스의 {@code @Component}라
 * 컴포넌트 스캔으로 잡히고, {@code @Import}·{@code @MockitoBean}처럼 컨텍스트 캐시 키를 바꾸지 않으므로 모든 통합
 * 테스트가 지금과 같은 컨텍스트를 그대로 쓴다. {@code @Primary}라 운영 {@code Clock} 빈(CLK-01)과 공존해도 이긴다.
 *
 * <p>동시성 테스트에서 여러 스레드가 함께 읽으므로 현재 시각을 {@link AtomicReference}로 둔다. 컨텍스트가 공유되니
 * 시계를 바꾼 테스트는 정리 단계에서 {@link #reset()}해야 한다({@code ReportCreateIntegrationSupport.cleanUp}).
 */
@Primary
@Component
public class MutableClock extends Clock {

    /** 2026-11-15 한국 시각 10:00. 자정 경계에서 멀어 날짜가 갈라질 일이 없다. */
    public static final Instant DEFAULT = Instant.parse("2026-11-15T01:00:00Z");

    private final AtomicReference<Instant> now;

    public MutableClock() {
        this(DEFAULT);
    }

    public MutableClock(Instant initial) {
        this.now = new AtomicReference<>(initial);
    }

    public void set(Instant instant) {
        now.set(instant);
    }

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    public void reset() {
        set(DEFAULT);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    /** 같은 시각을 다른 시간대로 보는 창. 이 시계를 움직이면 함께 움직인다. */
    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock source = this;
        return new Clock() {
            @Override
            public Instant instant() {
                return source.instant();
            }

            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return source.withZone(other);
            }
        };
    }
}
