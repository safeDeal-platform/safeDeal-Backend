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
     * <p><b>이미 찜한 상태여도 실패로 만들지 않는다.</b> 명세가 멱등 처리를 허용하고, 토글
     * 버튼은 연타·중복 탭이 흔하다. 두 번 눌러도 최종 상태("찜됨")는 같으므로 성공이 맞다.
     * 중복 판정은 서비스가 아니라 DB의 UNIQUE 제약이 한다 — 여기서 미리 조회해 봐야 조회와
     * 삽입 사이에 다른 요청이 끼어들 수 있다.
     *
     * <p>ACTIVE 매물만 찜할 수 있다. 팔렸거나 내려간 매물을 새로 찜하면 알림이 나갈 일이 없어
     * 기준가만 쌓인다. (이미 찜해둔 매물이 나중에 팔리는 것은 별개 — 그 행은 그대로 둔다.)
     */
    public FavoriteToggleResponse add(Long userId, String publicId) {
        Listing listing = findExisting(publicId);
        if (!listing.isListable()) {
            // 404가 아니라 409다. 상세 조회는 팔린 매물도 정상 응답하므로, 여기서 "찾을 수
            // 없습니다"를 주면 방금 화면에 띄운 매물이 없다는 뜻이 되어 클라이언트가 링크가
            // 깨진 것으로 오인한다. 존재하지만 상태 때문에 거부하는 경우다.
            throw new BusinessException(
                    CommonErrorCode.CONFLICT, "판매 중인 매물만 찜할 수 있습니다.");
        }

        listingFavoriteRepository.insertIfAbsent(
                userId, listing.getId(), listing.getPrice(), Instant.now());
        return FavoriteToggleResponse.on();
    }

    /**
     * 찜 해제. 행을 지운다 — 본인 데이터이고 분쟁 증거 가치가 없다.
     *
     * <p>찜하지 않은 매물을 해제해도 성공으로 돌려준다(명세가 멱등 허용). 최종 상태가 같고,
     * 404를 던지면 화면에서 하트가 이미 꺼져 있는데 오류만 뜬다.
     *
     * <p>삭제된 매물의 찜도 해제할 수 있어야 한다 — 목록에 남아 있으니 지울 수단이 있어야 한다.
     */
    public FavoriteToggleResponse remove(Long userId, String publicId) {
        Listing listing = listingRepository.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다."));

        listingFavoriteRepository.deleteByUserIdAndListingId(userId, listing.getId());
        return FavoriteToggleResponse.off();
    }

    /** 소프트 삭제된 매물은 없는 것으로 본다 — 새로 찜할 대상이 아니다. */
    private Listing findExisting(String publicId) {
        return listingRepository.findByPublicIdAndDeletedAtIsNull(publicId)
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.RESOURCE_NOT_FOUND, "매물을 찾을 수 없습니다."));
    }
}
