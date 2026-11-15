package com.safedeal.domain.report;

import com.safedeal.domain.chat.entity.ChatMessage;
import com.safedeal.domain.chat.entity.ChatRoom;
import com.safedeal.domain.chat.repository.ChatMessageRepository;
import com.safedeal.domain.chat.repository.ChatRoomRepository;
import com.safedeal.domain.listing.entity.ItemCondition;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.domain.report.dto.ReportCreateRequest;
import com.safedeal.domain.report.entity.ReportReason;
import com.safedeal.domain.report.entity.ReportTargetType;
import com.safedeal.domain.report.repository.ReportRepository;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.ErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import com.safedeal.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신고 접수 통합 테스트(실제 MySQL·Redis)의 공통 준비물.
 *
 * <p><b>{@code @Transactional}을 붙이지 않는다</b> — 접수 서비스가 트랜잭션 경계를 직접 나누고(UNIQUE 충돌 후
 * 새 트랜잭션 재시도), 동시성 테스트는 별도 스레드라 테스트 트랜잭션 롤백이 통하지 않는다
 * ({@link IntegrationTestSupport} 주석). 대신 {@link #cleanUp()}에서 만든 것을 직접 지운다.
 */
public abstract class ReportCreateIntegrationSupport extends IntegrationTestSupport {

    @Autowired protected ReportRepository reportRepository;
    @Autowired protected ListingRepository listingRepository;
    @Autowired protected CategoryRepository categoryRepository;
    @Autowired protected ChatRoomRepository chatRoomRepository;
    @Autowired protected ChatMessageRepository chatMessageRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PublicIdGenerator publicIdGenerator;
    @Autowired protected StringRedisTemplate redisTemplate;
    @Autowired protected JdbcTemplate jdbcTemplate;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reportRepository.deleteAllInBatch();
        chatMessageRepository.deleteAllInBatch();
        chatRoomRepository.deleteAllInBatch();
        listingRepository.deleteAllInBatch();
        userRepository.deleteAllByIdInBatch(createdUserIds);
        createdUserIds.clear();
        Set<String> limitKeys = redisTemplate.keys("rate:v1:*:report:*");
        if (limitKeys != null && !limitKeys.isEmpty()) {
            redisTemplate.delete(limitKeys);
        }
    }

    // ── 데이터 준비 ──────────────────────────────────────────────

    protected User user() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = userRepository.saveAndFlush(
                User.createLocal("rpt-" + suffix + "@safedeal.test", "hash", "rpt" + suffix));
        createdUserIds.add(user.getId());
        return user;
    }

    /** 계정 상태는 관리자 도메인이 바꾸는 값이라 엔티티에 setter가 없다 — SQL로 직접 바꾼다. */
    protected void changeStatus(User user, String status) {
        jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", status, user.getId());
    }

    protected Listing activeListing(User seller) {
        return listingRepository.saveAndFlush(Listing.register(
                publicIdGenerator.generate(), seller.getId(), "신고 테스트용 매물", "설명", 500_000,
                categoryRepository.findByCode("DIGITAL_PHONE").orElseThrow(),
                ItemCondition.USED, "서울특별시", "강남구", false));
    }

    protected ChatRoom room(Listing listing, User buyer) {
        return chatRoomRepository.saveAndFlush(ChatRoom.open(
                publicIdGenerator.generate(), listing.getId(), buyer.getId(), listing.getSellerId()));
    }

    protected Long message(ChatRoom room, User sender) {
        return chatMessageRepository.saveAndFlush(ChatMessage.write(
                room.getId(), sender.getId(), "문제 발언", UUID.randomUUID().toString())).getId();
    }

    protected static ReportCreateRequest listingRequest(Listing listing, ReportReason reason, String detail) {
        return new ReportCreateRequest(ReportTargetType.LISTING, listing.getPublicId(), reason, detail, null);
    }

    protected static ReportCreateRequest chatRequest(ChatRoom room, Long evidenceMessageId, String detail) {
        return new ReportCreateRequest(ReportTargetType.CHAT, room.getPublicId(), ReportReason.ABUSE, detail,
                evidenceMessageId);
    }

    protected static ErrorCode errorCodeOf(Object outcome) {
        assertThat(outcome).isInstanceOf(BusinessException.class);
        return ((BusinessException) outcome).getErrorCode();
    }

    // ── 동시 실행 ──────────────────────────────────────────────

    /**
     * 작업들을 같은 순간에 출발시키고, 각 작업의 결과(반환값 또는 던진 예외)를 순서대로 돌려준다.
     * 고정 sleep 없이 배리어로 경합 창을 맞춘다. 결과를 못 모으면 스레드를 남기지 않고 끝낸다.
     */
    protected static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
            // 인터럽트를 무시하고 도는 DB 작업이 정리(cleanUp) 뒤에 커밋하지 않도록 끝날 때까지 기다린다.
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
