package com.safedeal.domain.listing.service;

import com.safedeal.domain.listing.dto.ListingCreateRequest;
import com.safedeal.domain.listing.dto.ListingCreateResponse;
import com.safedeal.domain.listing.entity.Category;
import com.safedeal.domain.listing.entity.Listing;
import com.safedeal.domain.listing.repository.CategoryRepository;
import com.safedeal.domain.listing.repository.ListingRepository;
import com.safedeal.global.exception.BusinessException;
import com.safedeal.global.exception.CommonErrorCode;
import com.safedeal.global.util.PublicIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        Category category = categoryRepository.findByCode(request.categoryCode())
                .orElseThrow(() -> new BusinessException(
                        CommonErrorCode.INVALID_INPUT, "존재하지 않는 카테고리입니다."));

        if (!category.isActive()) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "더 이상 사용하지 않는 카테고리입니다.");
        }
        // 대분류에 매물이 달리면 가격통계 집계 단위가 무너진다. 엔티티도 같은 검사를 하지만,
        // 여기서 걸러야 사용자에게 400과 사유를 돌려줄 수 있다.
        if (!category.isLeaf()) {
            throw new BusinessException(
                    CommonErrorCode.INVALID_INPUT, "중분류를 선택해야 합니다.");
        }

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
}
