package com.oussamaksantini.insightstudio.security;

import com.oussamaksantini.insightstudio.account.UserQueries;
import com.oussamaksantini.insightstudio.tenancy.DemoProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Server-side session authentication for the API (docs/accounts-contract.md §1, §4).
 *
 * <ul>
 *   <li>Deny by default: only the endpoints listed below are public; everything else requires a
 *       signed-in session (401 problem detail otherwise).</li>
 *   <li>No form login, HTTP Basic or logout pages: the account endpoints sign in and out.</li>
 *   <li>SPA CSRF: readable {@code XSRF-TOKEN} cookie echoed in {@code X-XSRF-TOKEN} on every
 *       state-changing request (403 problem detail otherwise).</li>
 *   <li>Business-scoped reads are open to anonymous callers only when the public demo is enabled;
 *       {@code CurrentBusiness} then restricts them to the demo business, read-only.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /** Read endpoints served for the public demo when {@code insight.demo.public=true}. */
    static final String[] DEMO_READABLE = {
        "/api/dashboard/**", "/api/products/**", "/api/sales/**", "/api/stores/**", "/api/reports/**",
        "/api/analytics/**",
    };

    static final String[] PUBLIC_POSTS = {
        "/api/auth/sign-up", "/api/auth/sign-in", "/api/auth/password/forgot", "/api/auth/password/reset",
        "/api/invitations/preview", "/api/auth/verify-email",
    };

    /** Payment provider webhooks: authenticated by their signature, so neither a session nor CSRF applies. */
    static final String WEBHOOKS = "/api/billing/webhooks/**";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            DemoProperties demo,
            UserQueries users,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfTokens) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        // Error dispatches only render the error of a request that was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/session", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POSTS).permitAll()
                        .requestMatchers(HttpMethod.POST, WEBHOOKS).permitAll()
                        .requestMatchers(HttpMethod.GET, DEMO_READABLE).access(signedInOrPublicDemo(demo))
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .ignoringRequestMatchers(WEBHOOKS)
                        .csrfTokenRequestHandler(new HeaderCsrfTokenRequestHandler()))
                .securityContext(context -> context.securityContextRepository(contextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(ProblemResponses.unauthorized())
                        .accessDeniedHandler(ProblemResponses.forbidden()))
                .addFilterBefore(new SessionVersionFilter(users), AnonymousAuthenticationFilter.class)
                .build();
    }

    /** The signed-in account lives in the (PostgreSQL-backed, shared) HTTP session as plain values. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new SessionAccountContextRepository();
    }

    /**
     * Runs before every other filter (sessions, security), so rate limits and HSTS see the client
     * address and scheme resolved from trusted proxies only.
     */
    @Bean
    FilterRegistrationBean<TrustedProxyFilter> trustedProxyFilter(SecurityProperties properties) {
        FilterRegistrationBean<TrustedProxyFilter> registration =
                new FilterRegistrationBean<>(new TrustedProxyFilter(properties.trustedProxies()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    CsrfTokenRepository csrfTokenRepository(SecurityProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Lax");
            if (properties.cookieSecure()) {
                cookie.secure(true);
            }
        });
        return repository;
    }

    private static AuthorizationManager<RequestAuthorizationContext> signedInOrPublicDemo(DemoProperties demo) {
        AuthenticationTrustResolver trust = new AuthenticationTrustResolverImpl();
        return (authentication, context) -> {
            Authentication current = authentication.get();
            boolean signedIn = current != null && trust.isAuthenticated(current);
            return new AuthorizationDecision(signedIn || demo.publicAccess());
        };
    }
}
