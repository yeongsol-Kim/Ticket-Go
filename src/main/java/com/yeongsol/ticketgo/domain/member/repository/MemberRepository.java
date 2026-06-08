package com.yeongsol.ticketgo.domain.member.repository;

import com.yeongsol.ticketgo.domain.member.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * 이메일로 회원 조회
     * 로그인, 회원가입 시 중복 체크용
     */
    Optional<Member> findByEmail(String email);

    /**
     * 이메일 존재 여부 확인
     * 회원가입 시 중복 체크용
     */
    boolean existsByEmail(String email);

    /**
     * 이메일과 활성화 상태로 조회
     * 로그인 시 사용
     */
    Optional<Member> findByEmailAndEnabled(String email, Boolean enabled);
}
