package com.oussamaksantini.insightstudio.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.oussamaksantini.insightstudio.account.AccountPrincipal;
import com.oussamaksantini.insightstudio.tenancy.Role;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Accounts for integration tests: inserts users and memberships with SQL, and builds MockMvc
 * requests authenticated as a user (the same {@link AccountPrincipal} a real sign-in stores in the
 * session) with the {@code X-Business-Id} selector and a CSRF header.
 */
public final class TestAccounts {

    /** Password of every user created here. */
    public static final String PASSWORD = "correct horse battery staple";
    /** Hashed once: bcrypt is deliberately slow. */
    private static final String PASSWORD_HASH =
            PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(PASSWORD);
    public static final String BUSINESS_HEADER = "X-Business-Id";

    private final JdbcTemplate jdbc;

    public TestAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record TestUser(long id, String email, int sessionVersion) {
    }

    /** A user whose email address is verified (like every account before verification existed). */
    public TestUser user(String email) {
        long id = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, display_name, email_verified_at) VALUES (?, ?, ?, now()) RETURNING id",
                Long.class, email, PASSWORD_HASH, email.substring(0, email.indexOf('@')));
        return new TestUser(id, email, 0);
    }

    /** A user who has not verified their email address yet. */
    public TestUser unverifiedUser(String email) {
        long id = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, display_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, email, PASSWORD_HASH, email.substring(0, email.indexOf('@')));
        return new TestUser(id, email, 0);
    }

    public void member(TestUser user, long businessId, Role role) {
        jdbc.update("INSERT INTO memberships (user_id, business_id, role) VALUES (?, ?, ?)",
                user.id(), businessId, role.name());
    }

    /** A new user with {@code role} in {@code businessId}. */
    public TestUser member(String email, long businessId, Role role) {
        TestUser user = user(email);
        member(user, businessId, role);
        return user;
    }

    /** Authenticated as {@code user}, without choosing a business. */
    public static RequestPostProcessor as(TestUser user) {
        AccountPrincipal principal = new AccountPrincipal(user.id(), user.email(), user.sessionVersion());
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /** Authenticated as {@code user}, selecting {@code businessId}, with a CSRF header for writes. */
    public static RequestPostProcessor as(TestUser user, long businessId) {
        RequestPostProcessor auth = as(user);
        RequestPostProcessor csrf = csrf();
        return request -> {
            request.addHeader(BUSINESS_HEADER, Long.toString(businessId));
            return csrf.postProcessRequest(auth.postProcessRequest(request));
        };
    }

    /**
     * A valid CSRF pair the way a browser sends it: the {@code XSRF-TOKEN} cookie and the same value
     * in {@code X-XSRF-TOKEN}. (Spring Security's own {@code csrf()} post-processor is not used: it
     * swaps the shared filter's token repository for a session-based one, which would break the
     * cookie for every later test in the same context.)
     */
    public static RequestPostProcessor csrf() {
        return csrf(true);
    }

    /** A CSRF cookie with a header that does not match it. */
    public static RequestPostProcessor invalidCsrf() {
        return csrf(false);
    }

    private static RequestPostProcessor csrf(boolean valid) {
        return request -> {
            String token = UUID.randomUUID().toString();
            List<Cookie> cookies = new ArrayList<>(request.getCookies() == null ? List.of() : Arrays.asList(request.getCookies()));
            cookies.add(new Cookie("XSRF-TOKEN", token));
            request.setCookies(cookies.toArray(Cookie[]::new));
            request.addHeader("X-XSRF-TOKEN", valid ? token : UUID.randomUUID().toString());
            return request;
        };
    }

    /** MockMvc whose every request is made by {@code user} for {@code businessId}. */
    public static MockMvc mvc(WebApplicationContext context, TestUser user, long businessId) {
        MockHttpServletRequestBuilder defaults = get("/").with(as(user, businessId));
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).defaultRequest(defaults).build();
    }

    /**
     * Creates an OWNER of {@code businessId} and returns MockMvc acting as that owner: how the
     * endpoint tests written before accounts existed now call the API.
     */
    public static MockMvc ownerMvc(WebApplicationContext context, JdbcTemplate jdbc, long businessId) {
        TestUser owner = new TestAccounts(jdbc).member("owner-" + businessId + "@example.com", businessId, Role.OWNER);
        return mvc(context, owner, businessId);
    }
}
