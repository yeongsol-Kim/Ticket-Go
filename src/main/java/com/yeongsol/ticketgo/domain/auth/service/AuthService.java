package com.yeongsol.ticketgo.domain.auth.service;

import com.yeongsol.ticketgo.config.security.JwtTokenProvider;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 인증 서비스
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 로그인
     */
    public String login(String email, String password) {
        // 회원 조회
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("이메일 또는 비밀번호가 일치하지 않습니다"));

        // 비활성화된 회원 체크
        if (!member.getEnabled()) {
            throw new BadCredentialsException("비활성화된 회원입니다");
        }

        // 비밀번호 확인
        if (!passwordEncoder.matches(password, member.getPassword())) {
            throw new BadCredentialsException("이메일 또는 비밀번호가 일치하지 않습니다");
        }

        // JWT 토큰 생성
        return jwtTokenProvider.createToken(
                member.getId(),
                member.getEmail(),
                List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()))
        );
    }
}
