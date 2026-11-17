package com.safedeal.domain.user.service;

import com.safedeal.domain.auth.exception.AuthErrorCode;
import com.safedeal.domain.user.dto.MyProfileResponse;
import com.safedeal.domain.user.entity.User;
import com.safedeal.domain.user.entity.UserStatus;
import com.safedeal.domain.user.repository.UserRepository;
import com.safedeal.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository userRepository;

    /**
     * 내 정보. 잠금 없는 일반 조회라 신뢰도 갱신(행 잠금)과 겹쳐도 기다리지 않는다(ME-26).
     * 계정이 없거나 탈퇴했으면(탈퇴 직후 아직 살아 있는 토큰) 401 AUTH004(Q2, 신고 접수 D5와 같은 규칙),
     * 정지·차단 계정이면 로그인과 같은 403 AUTH005(Q4). 이메일 미인증은 그대로 보여 준다(Q6).
     */
    public MyProfileResponse getMyProfile(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_TOKEN));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(AuthErrorCode.ACCOUNT_NOT_ACTIVE);
        }
        return MyProfileResponse.from(user);
    }
}
