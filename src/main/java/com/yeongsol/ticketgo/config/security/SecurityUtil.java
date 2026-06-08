package com.yeongsol.ticketgo.config.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Security 유틸리티
 * SecurityContext에서 현재 인증된 사용자 정보 추출
 */
@Component
public class SecurityUtil {

    private static JwtTokenProvider jwtTokenProvider;

    @Autowired
    public SecurityUtil(JwtTokenProvider jwtTokenProvider) {
        SecurityUtil.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * 현재 인증된 사용자의 Member ID 가져오기
     */
    public static Long getCurrentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("인증되지 않은 사용자입니다");
        }

        // JWT 토큰에서 Member ID 추출
        String token = (String) authentication.getCredentials();
        if (token != null) {
            return jwtTokenProvider.getMemberIdFromToken(token);
        }

        throw new IllegalStateException("인증 정보를 찾을 수 없습니다");
    }

    /**
     * 현재 인증된 사용자의 이메일 가져오기
     */
    public static String getCurrentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("인증되지 않은 사용자입니다");
        }

        return authentication.getName();
    }
}
