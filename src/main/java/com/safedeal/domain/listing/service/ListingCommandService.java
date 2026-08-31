package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingCreateRequest;
import com.safedeal.domain.listing.dto.ListingCreateResponse;
import com.safedeal.domain.listing.dto.ListingStatusChangeRequest;
import com.safedeal.domain.listing.dto.ListingStatusChangeResponse;
import com.safedeal.domain.listing.dto.ListingUpdateRequest;
import com.safedeal.domain.listing.dto.ListingUpdateResponse;
import com.safedeal.domain.listing.exception.ListingErrorCode;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.entity.ListingStatus;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 매물 쓰기. 조회는 {@link ListingQueryService}가 담당한다 — 정책이 서비스 계층만 CQRS로
 * 나누기로 했다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ListingCommandService {

    private final ListingRepository listingRepository;
    private final CategoryRepository categoryRepository;
    private final PublicIdGenerator publicIdGenerator;

    /**
     * 검증 구간 on/off. 검증 도메인이 아직 없어 기본값은 off이고, 이때 등록은 곧바로 ACTIVE가 된다.
     * 상태를 코드에 하드코딩하지 않는 이유는 검증이 붙을 때 등록 로직을 다시 쓰지 않기 위해서다.
     */
    @Value("${app.verification.enabled:false}")
    private boolean verificationEnabled;

    public ListingCreateResponse register(Long sellerId, ListingCreateRequest request) {
        // 대분류에 매물이 달리면 가격통계 집계 단위가 무너진다. 엔티티도 같은 검사를 하지만,
        // 여기서 걸러야 사용자에게 400과 사유를 돌려줄 수 있다.
        Category category = loadUsableLeafCategory(request.categoryCode());

        Listing listing = Listing.register(
                publicIdGenerator.generate(),
                sellerId,
                request.title(),
                request.description(),
                request.price(),
                category,
                request.itemCondition(),
                request.regionSido(),
                request.regionSigungu(),
                verificationEnabled);

        return ListingCreateResponse.from(listingRepository.save(listing));
    }

    /**
     * 매물 수정. 판매자 본인만, 공개·검증대기 상태에서만 가능하다.
     *
     * <p>버전을 클라이언트가 보내게 하는 이유: 어떤 판을 보고 고쳤는지 알아야, 그 사이 다른
     * 수정이 반영됐을 때 덮어쓰지 않고 409로 되돌려줄 수 있다.
     */
    public ListingUpdateResponse update(Long sellerId, String publicId, ListingUpdateRequest request) {
        Listing listing = loadOwned(sellerId, publicId);

        if (!listing.getStatus().isEditable()) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "지금 상태에서는 수정할 수 없습니다.");
        }
        if (!request.version().equals(listing.getVersion())) {
            throw new BusinessException(CommonErrorCode.CONCURRENT_MODIFICATION);
        }

        Category category = loadUsableLeafCategory(request.categoryCode());
        try {
            listing.update(request.title(), request.description(), request.price(),
                    category, request.itemCondition(), LocalDate.now(LIMIT_ZONE));
        } catch (Listing.PriceDropLimitExceededException e) {
            throw new BusinessException(ListingErrorCode.PRICE_DROP_LIMIT_EXCEEDED);
        }
        // 버전은 flush 시점에 올라간다. 먼저 내보내면 클라이언트가 방금 쓴 값과 같은 번호를
        // 받아, 다음 수정에서 자기 변경과 충돌한다.
        listingRepository.flush();
        return ListingUpdateResponse.from(listing);
    }

    /** 매물 삭제. 소프트 삭제이며 제재된 매물은 지울 수 없다(제재 근거 보존). */
    public void delete(Long sellerId, String publicId) {
        Listing listing = loadOwned(sellerId, publicId);

        // 제재 건만 403이다(명세). 그 외 전이 불가 상태는 잘못된 요청이라 400으로 구분한다.
        if (listing.getStatus() == ListingStatus.BLOCKED) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "제재된 매물은 삭제할 수 없습니다.");
        }
        try {
            listing.softDelete(Instant.now());
        } catch (IllegalStateException e) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "지금 상태에서는 삭제할 수 없습니다.");
        }
    }

    /**
     * 조회수 증가.
     *
     * <p>엔티티를 고쳐 저장하지 않고 벌크 UPDATE로 올린다. 더티체킹으로 올리면
     * {@code @Version}이 함께 증가해, 남이 상세를 열어본 것만으로 판매자의 수정이 낙관적 락
     * 충돌로 실패한다.
     *
     * <p>판매자 본인과 관리자는 세지 않는다 — 자기 매물을 열어보며 숫자를 올리는 것을 막는다.
     * 조건을 SQL에 실어 조회를 한 번 더 하지 않는다.
     *
     * <p>중복 제거는 하지 않는다(정책: 표시용). 같은 사람이 여러 번 열면 여러 번 오른다.
     */
    public void increaseViewCount(String publicId, Long viewerId, boolean viewerIsAdmin) {
        if (viewerIsAdmin) {
            return;
        }
        listingRepository.increaseViewCount(publicId, viewerId);
    }

    /**
     * 판매자 수동 상태 전이.
     *
     * <p>엔티티를 고쳐 저장하지 않고 조건부 UPDATE를 던진다. 읽고-판단하고-쓰는 사이에 다른
     * 요청이 끼어들면 둘 다 자기 판단으로는 옳은 결과를 쓰게 되므로, 기대 상태를 WHERE에
     * 실어 DB가 한 쪽만 성공시키게 한다. 영향 행이 0이면 이미 다른 전이가 선점한 것이다.
     */
    public ListingStatusChangeResponse changeStatus(
            Long sellerId, String publicId, ListingStatusChangeRequest request) {

        Listing listing = loadOwned(sellerId, publicId);

        int affected = switch (request.action()) {
            case MARK_SOLD -> listingRepository.markSoldByOwner(
                    listing.getId(), sellerId, Instant.now());
            case RESTORE_MANUAL_SOLD -> listingRepository.restoreManualSoldByOwner(
                    listing.getId(), sellerId, Instant.now().minus(MANUAL_SOLD_RESTORE_WINDOW));
        };
        if (affected == 0) {
            throw new BusinessException(
                    CommonErrorCode.CONFLICT, "지금 상태에서는 처리할 수 없습니다.");
        }

        // 조건부 UPDATE는 영속성 컨텍스트를 우회하므로 방금 쓴 값을 다시 읽어 응답한다.
        return ListingStatusChangeResponse.from(
                listingRepository.findByPublicId(publicId).orElseThrow());
    }

    /** 수동 판매완료를 되돌릴 수 있는 시간. 실수는 대개 곧바로 알아차린다. */
    private static final Duration MANUAL_SOLD_RESTORE_WINDOW = Duration.ofHours(24);

    /**
     * "하루 2회"의 하루를 세는 기준 시간대.
     *
     * <p>저장은 정책대로 UTC지만, 이 한도는 판매자가 체감하는 날짜로 끊어야 한다. UTC로 세면
     * 한국 시각 오전 9시에 날짜가 바뀌어, 아침에 두 번 내린 판매자가 오전 중에 두 번 더 내릴
     * 수 있게 된다.
     */
    private static final ZoneId LIMIT_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 소유자 확인까지 마친 매물을 꺼낸다.
     *
     * <p>남의 매물이면 404가 아니라 403이다 — 존재 자체는 목록·상세로 이미 공개돼 있어
     * 숨길 것이 없고, 권한 문제임을 알려주는 편이 낫다.
     */
    private Listing loadOwned(Long sellerId, String publicId) {
        Listing listing = listingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다."));
        if (!listing.isOwnedBy(sellerId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "판매자 본인만 가능합니다.");
        }
        return listing;
    }

    private Category loadUsableLeafCategory(String categoryCode) {
        Category category = categoryRepository.findByCode(categoryCode)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.INVALID_INPUT, "존재하지 않는 카테고리입니다."));
        if (!category.isActive()) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "더 이상 사용하지 않는 카테고리입니다.");
        }
        if (!category.isLeaf()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "중분류를 선택해야 합니다.");
        }
        return category;
    }
}
