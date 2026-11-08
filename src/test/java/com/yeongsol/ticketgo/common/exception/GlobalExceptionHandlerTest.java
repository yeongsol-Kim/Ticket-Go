package com.yeongsol.ticketgo.common.exception;

import com.yeongsol.ticketgo.common.dto.ErrorResponse;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobalExceptionHandler 단위 테스트")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("만료된 토큰은 500이 아니라 401(A001)로 응답한다")
    void expiredToken_returns401() {
        ResponseEntity<ErrorResponse> response =
                handler.handleJwtException(new ExpiredJwtException(null, null, "JWT expired"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getCode()).isEqualTo("A001");
    }

    @Test
    @DisplayName("형식이 깨진 토큰도 401로 응답한다")
    void malformedToken_returns401() {
        ResponseEntity<ErrorResponse> response =
                handler.handleJwtException(new MalformedJwtException("bad token"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
