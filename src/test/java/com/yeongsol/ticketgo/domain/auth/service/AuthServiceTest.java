package com.yeongsol.ticketgo.domain.auth.service;

import com.yeongsol.ticketgo.config.security.JwtTokenProvider;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.model.MemberRole;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Collection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * AuthService 단위 테스트
 * Mock을 사용하여 의존성 제거
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @InjectMocks
    private AuthService authService;

    @Test
    @DisplayName("로그인 성공 - JWT 토큰 반환")
    void login_success() {
        // Given
        String email = "test@example.com";
        String rawPassword = "password123";
        String expectedToken = "jwt.token.here";

        Member mockMember = createMockMember(1L, email, "encodedPassword", true);
        given(memberRepository.findByEmail(email)).willReturn(Optional.of(mockMember));
        given(passwordEncoder.matches(rawPassword, mockMember.getPassword())).willReturn(true);
        given(jwtTokenProvider.createToken(eq(1L), eq(email), any())).willReturn(expectedToken);

        // When
        String result = authService.login(email, rawPassword);

        // Then
        assertThat(result).isEqualTo(expectedToken);
        then(memberRepository).should(times(1)).findByEmail(email);
        then(passwordEncoder).should(times(1)).matches(rawPassword, mockMember.getPassword());
        then(jwtTokenProvider).should(times(1)).createToken(eq(1L), eq(email), any());
    }

    @Test
    @DisplayName("로그인 실패 - 존재하지 않는 이메일")
    void login_emailNotFound_throwsException() {
        // Given
        String email = "notfound@example.com";
        String rawPassword = "password123";

        given(memberRepository.findByEmail(email)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> authService.login(email, rawPassword))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("이메일 또는 비밀번호가 일치하지 않습니다");
        then(memberRepository).should(times(1)).findByEmail(email);
        then(passwordEncoder).should(never()).matches(anyString(), anyString());
        then(jwtTokenProvider).should(never()).createToken(any(), anyString(), any());
    }

    @Test
    @DisplayName("로그인 실패 - 비활성화된 회원")
    void login_disabledMember_throwsException() {
        // Given
        String email = "disabled@example.com";
        String rawPassword = "password123";

        Member disabledMember = createMockMember(1L, email, "encodedPassword", false);
        given(memberRepository.findByEmail(email)).willReturn(Optional.of(disabledMember));

        // When & Then
        assertThatThrownBy(() -> authService.login(email, rawPassword))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("비활성화된 회원입니다");
        then(memberRepository).should(times(1)).findByEmail(email);
        then(passwordEncoder).should(never()).matches(anyString(), anyString());
        then(jwtTokenProvider).should(never()).createToken(any(), anyString(), any());
    }

    @Test
    @DisplayName("로그인 실패 - 잘못된 비밀번호")
    void login_wrongPassword_throwsException() {
        // Given
        String email = "test@example.com";
        String wrongPassword = "wrongPassword";

        Member mockMember = createMockMember(1L, email, "encodedPassword", true);
        given(memberRepository.findByEmail(email)).willReturn(Optional.of(mockMember));
        given(passwordEncoder.matches(wrongPassword, mockMember.getPassword())).willReturn(false);

        // When & Then
        assertThatThrownBy(() -> authService.login(email, wrongPassword))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("이메일 또는 비밀번호가 일치하지 않습니다");
        then(memberRepository).should(times(1)).findByEmail(email);
        then(passwordEncoder).should(times(1)).matches(wrongPassword, mockMember.getPassword());
        then(jwtTokenProvider).should(never()).createToken(any(), anyString(), any());
    }

    // Helper method
    private Member createMockMember(Long id, String email, String password, boolean enabled) {
        Member member = Member.builder()
                .email(email)
                .password(password)
                .name("테스트 회원")
                .phoneNumber("010-1234-5678")
                .role(MemberRole.USER)
                .build();

        setMemberFields(member, id, enabled);
        return member;
    }

    private void setMemberFields(Member member, Long id, boolean enabled) {
        try {
            var idField = Member.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(member, id);

            var enabledField = Member.class.getDeclaredField("enabled");
            enabledField.setAccessible(true);
            enabledField.set(member, enabled);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
