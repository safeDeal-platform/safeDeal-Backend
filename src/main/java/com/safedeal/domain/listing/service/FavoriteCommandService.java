package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.FavoriteToggleResponse;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.ListingFavoriteRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class FavoriteCommandService {

    private final ListingRepository listingRepository;
    private final ListingFavoriteRepository listingFavoriteRepository;

    /**
     * 찜 등록.
     *
     * <p>이미 찜한 상태여도 실패로 만들지 않는다(멱등, 명세) — 중복 판정은 서비스가 아니라 DB의
     * UNIQUE 제약이 한다. 여기서 미리 조회하면 조회와 삽입 사이에 다른 요청이 끼어들 수 있다.
     *
     * <p>ACTIVE 매물만 찜할 수 있고, 그 외 상태는 없는 매물과 같은 404다(C002) — 상태별로
     * 응답을 가르면 남의 비공개 매물(제재·검증대기) 상태가 새어 나간다.
     */
    public FavoriteToggleResponse add(Long userId, String publicId) {
        Listing listing = findExisting(publicId);
        if (!listing.isListable()) {
            throw notFound();
        }

        listingFavoriteRepository.insertIfAbsent(
                userId, listing.getId(), listing.getPrice(), Instant.now());
        return FavoriteToggleResponse.on();
    }

    /**
     * 찜 해제. 행을 지운다 — 본인 데이터이고 분쟁 증거 가치가 없다.
     *
     * <p>찜하지 않았거나 없는 매물이어도 성공으로 돌려준다(멱등, 명세) — 매물 존재 여부로
     * 응답을 가르지 않아 존재 여부 노출도 막힌다. 삭제된 매물의 찜도 해제할 수 있어야 한다 —
     * 목록에 남아 있으니 지울 수단이 있어야 한다.
     */
    public FavoriteToggleResponse remove(Long userId, String publicId) {
        listingFavoriteRepository.deleteByUserIdAndListingPublicId(userId, publicId);
        return FavoriteToggleResponse.off();
    }

    /** 소프트 삭제된 매물은 없는 것으로 본다 — 새로 찜할 대상이 아니다. */
    private Listing findExisting(String publicId) {
        return listingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(FavoriteCommandService::notFound);
    }

    private static BusinessException notFound() {
        return new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다.");
    }
}
