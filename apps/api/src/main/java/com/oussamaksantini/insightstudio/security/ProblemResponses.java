package com.oussamaksantini.insightstudio.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * RFC 9457 problem details for requests rejected by the security filter chain (before any
 * controller runs), matching what {@code ApiExceptionHandler} renders for controller errors.
 */
final class ProblemResponses {

    static final String SIGN_IN = "Sign in to continue.";
    static final String CSRF = "Missing or invalid CSRF token. Reload the page and try again.";
    static final String FORBIDDEN = "You are not allowed to do this.";

    private ProblemResponses() {
    }

    /** 401 for anonymous requests to anything that requires a session. */
    static AuthenticationEntryPoint unauthorized() {
        return (request, response, ex) -> write(request, response, HttpStatus.UNAUTHORIZED, SIGN_IN);
    }

    /** 403 for a missing/invalid CSRF token or a denied authenticated request. */
    static AccessDeniedHandler forbidden() {
        return (request, response, ex) ->
                write(request, response, HttpStatus.FORBIDDEN, ex instanceof CsrfException ? CSRF : FORBIDDEN);
    }

    static void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}"
                .formatted(json(status.getReasonPhrase()), status.value(), json(detail), json(request.getRequestURI()));
        response.getWriter().write(body);
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
