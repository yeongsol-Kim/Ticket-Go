package com.yeongsol.ticketgo.domain.member.service;

import com.yeongsol.ticketgo.domain.member.exception.EmailAlreadyExistsException;
import com.yeongsol.ticketgo.domain.member.exception.MemberNotFoundException;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.model.MemberRole;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * MemberService 단위 테스트
 * Mock을 사용하여 Repository 의존성 제거
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberService 단위 테스트")
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberService;

    @Test
    @DisplayName("회원 가입 성공")
    void register_success() {
        // Given
        String email = "test@example.com";
        String rawPassword = "password123";
        String name = "테스트";
        String phoneNumber = "010-1234-5678";
        String encodedPassword = "encodedPassword";

        given(memberRepository.existsByEmail(email)).willReturn(false);
        given(passwordEncoder.encode(rawPassword)).willReturn(encodedPassword);
        given(memberRepository.save(any(Member.class))).willAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            setMemberId(member, 1L);
            return member;
        });

        // When
        Member result = memberService.register(email, rawPassword, name, phoneNumber);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(email);
        assertThat(result.getPassword()).isEqualTo(encodedPassword);
        assertThat(result.getName()).isEqualTo(name);
        assertThat(result.getPhoneNumber()).isEqualTo(phoneNumber);
        assertThat(result.getRole()).isEqualTo(MemberRole.USER);
        then(memberRepository).should(times(1)).existsByEmail(email);
        then(passwordEncoder).should(times(1)).encode(rawPassword);
        then(memberRepository).should(times(1)).save(any(Member.class));
    }

    @Test
    @DisplayName("회원 가입 실패 - 이메일 중복")
    void register_emailAlreadyExists_throwsException() {
        // Given
        String email = "existing@example.com";
        String rawPassword = "password123";
        String name = "테스트";
        String phoneNumber = "010-1234-5678";

        given(memberRepository.existsByEmail(email)).willReturn(true);

        // When & Then
        assertThatThrownBy(() -> memberService.register(email, rawPassword, name, phoneNumber))
                .isInstanceOf(EmailAlreadyExistsException.class);
        then(memberRepository).should(times(1)).existsByEmail(email);
        then(passwordEncoder).should(never()).encode(anyString());
        then(memberRepository).should(never()).save(any(Member.class));
    }

    @Test
    @DisplayName("이메일로 회원 조회 성공")
    void findByEmail_success() {
        // Given
        String email = "test@example.com";
        Member mockMember = createMockMember(1L, email);
        given(memberRepository.findByEmail(email)).willReturn(Optional.of(mockMember));

        // When
        Member result = memberService.findByEmail(email);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(email);
        then(memberRepository).should(times(1)).findByEmail(email);
    }

    @Test
    @DisplayName("이메일로 회원 조회 실패 - 존재하지 않는 이메일")
    void findByEmail_notFound_throwsException() {
        // Given
        String email = "notfound@example.com";
        given(memberRepository.findByEmail(email)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> memberService.findByEmail(email))
                .isInstanceOf(MemberNotFoundException.class);
        then(memberRepository).should(times(1)).findByEmail(email);
    }

    @Test
    @DisplayName("활성화된 회원 조회 성공")
    void findByEmailAndEnabled_success() {
        // Given
        String email = "test@example.com";
        Member mockMember = createMockMember(1L, email);
        given(memberRepository.findByEmailAndEnabled(email, true)).willReturn(Optional.of(mockMember));

        // When
        Member result = memberService.findByEmailAndEnabled(email);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getEmail()).isEqualTo(email);
        then(memberRepository).should(times(1)).findByEmailAndEnabled(email, true);
    }

    @Test
    @DisplayName("활성화된 회원 조회 실패 - 비활성화 또는 존재하지 않음")
    void findByEmailAndEnabled_notFound_throwsException() {
        // Given
        String email = "disabled@example.com";
        given(memberRepository.findByEmailAndEnabled(email, true)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> memberService.findByEmailAndEnabled(email))
                .isInstanceOf(MemberNotFoundException.class);
        then(memberRepository).should(times(1)).findByEmailAndEnabled(email, true);
    }

    @Test
    @DisplayName("회원 ID로 조회 성공")
    void findById_success() {
        // Given
        Long memberId = 1L;
        Member mockMember = createMockMember(memberId, "test@example.com");
        given(memberRepository.findById(memberId)).willReturn(Optional.of(mockMember));

        // When
        Member result = memberService.findById(memberId);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(memberId);
        then(memberRepository).should(times(1)).findById(memberId);
    }

    @Test
    @DisplayName("회원 ID로 조회 실패 - 존재하지 않는 ID")
    void findById_notFound_throwsException() {
        // Given
        Long memberId = 999L;
        given(memberRepository.findById(memberId)).willReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> memberService.findById(memberId))
                .isInstanceOf(MemberNotFoundException.class);
        then(memberRepository).should(times(1)).findById(memberId);
    }

    @Test
    @DisplayName("이메일 존재 여부 확인 - 존재함")
    void isEmailExists_true() {
        // Given
        String email = "existing@example.com";
        given(memberRepository.existsByEmail(email)).willReturn(true);

        // When
        boolean result = memberService.isEmailExists(email);

        // Then
        assertThat(result).isTrue();
        then(memberRepository).should(times(1)).existsByEmail(email);
    }

    @Test
    @DisplayName("이메일 존재 여부 확인 - 존재하지 않음")
    void isEmailExists_false() {
        // Given
        String email = "new@example.com";
        given(memberRepository.existsByEmail(email)).willReturn(false);

        // When
        boolean result = memberService.isEmailExists(email);

        // Then
        assertThat(result).isFalse();
        then(memberRepository).should(times(1)).existsByEmail(email);
    }

    // Helper method
    private Member createMockMember(Long id, String email) {
        Member member = Member.builder()
                .email(email)
                .password("encodedPassword")
                .name("테스트 회원")
                .phoneNumber("010-1234-5678")
                .role(MemberRole.USER)
                .build();

        setMemberId(member, id);
        return member;
    }

    private void setMemberId(Member member, Long id) {
        try {
            var idField = Member.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(member, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
