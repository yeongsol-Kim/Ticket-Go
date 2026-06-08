package com.yeongsol.ticketgo.domain.member.service;

import com.yeongsol.ticketgo.domain.member.exception.EmailAlreadyExistsException;
import com.yeongsol.ticketgo.domain.member.exception.MemberNotFoundException;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.model.MemberRole;
import com.yeongsol.ticketgo.domain.member.model.MemberTier;
import com.yeongsol.ticketgo.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 회원 가입
     */
    @Transactional
    public Member register(String email, String rawPassword, String name, String phoneNumber) {
        // 이메일 중복 체크
        if (memberRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException();
        }

        // 비밀번호 암호화
        String encodedPassword = encodePassword(rawPassword);

        Member member = Member.builder()
                .email(email)
                .password(encodedPassword)
                .name(name)
                .phoneNumber(phoneNumber)
                .role(MemberRole.USER)
                .build();

        return memberRepository.save(member);
    }

    /**
     * 이메일로 회원 조회
     */
    public Member findByEmail(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(MemberNotFoundException::new);
    }

    /**
     * 활성화된 회원 조회 (로그인용)
     */
    public Member findByEmailAndEnabled(String email) {
        return memberRepository.findByEmailAndEnabled(email, true)
                .orElseThrow(MemberNotFoundException::new);
    }

    /**
     * 회원 ID로 조회
     */
    public Member findById(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(MemberNotFoundException::new);
    }

    /**
     * 이메일 중복 확인
     */
    public boolean isEmailExists(String email) {
        return memberRepository.existsByEmail(email);
    }

    private String encodePassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }
}
