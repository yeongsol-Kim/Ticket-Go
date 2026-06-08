package com.yeongsol.ticketgo.domain.member.controller;

import com.yeongsol.ticketgo.config.security.SecurityUtil;
import com.yeongsol.ticketgo.domain.member.model.Member;
import com.yeongsol.ticketgo.domain.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 회원 관리 API
 */
@Tag(name = "Member", description = "회원 API")
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @Operation(summary = "회원 가입", description = "새로운 회원을 등록합니다")
    @PostMapping
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        Member member = memberService.register(
                request.email(),
                request.password(),
                request.name(),
                request.phoneNumber()
        );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MemberResponse.from(member));
    }

    @Operation(summary = "이메일 중복 확인", description = "회원 가입 시 이메일 중복 여부를 확인합니다")
    @GetMapping("/check-email")
    public ResponseEntity<?> checkEmail(@RequestParam String email) {
        boolean exists = memberService.isEmailExists(email);
        return ResponseEntity.ok(new EmailCheckResponse(exists));
    }

    @Operation(summary = "내 정보 조회", description = "로그인한 사용자의 정보를 조회합니다",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @GetMapping("/me")
    public ResponseEntity<?> getMyInfo() {
        Long memberId = SecurityUtil.getCurrentMemberId();

        Member member = memberService.findById(memberId);
        return ResponseEntity.ok(MemberResponse.from(member));
    }

    @Operation(summary = "회원 정보 조회", description = "특정 회원의 정보를 조회합니다 (관리자용)",
               security = @SecurityRequirement(name = "Bearer Authentication"))
    @GetMapping("/{id}")
    public ResponseEntity<?> getMember(@PathVariable Long id) {
        Member member = memberService.findById(id);
        return ResponseEntity.ok(MemberResponse.from(member));
    }

    // ===== Request/Response DTOs =====
    // TODO: 별도 dto 패키지로 분리 고려

    record RegisterRequest(
            @NotBlank(message = "이메일은 필수입니다")
            @Email(message = "올바른 이메일 형식이 아닙니다")
            String email,

            @NotBlank(message = "비밀번호는 필수입니다")
            @Size(min = 8, message = "비밀번호는 최소 8자 이상이어야 합니다")
            String password,

            @NotBlank(message = "이름은 필수입니다")
            String name,

            @NotBlank(message = "전화번호는 필수입니다")
            @Pattern(regexp = "^01[0-9]-?[0-9]{3,4}-?[0-9]{4}$",
                    message = "올바른 전화번호 형식이 아닙니다")
            String phoneNumber
    ) {}

    record MemberResponse(
            Long id,
            String email,
            String name,
            String phoneNumber,
            String role
    ) {
        static MemberResponse from(Member member) {
            return new MemberResponse(
                    member.getId(),
                    member.getEmail(),
                    member.getName(),
                    member.getPhoneNumber(),
                    member.getRole().name()
            );
        }
    }

    record EmailCheckResponse(
            boolean exists
    ) {}
}
