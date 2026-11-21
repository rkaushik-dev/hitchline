package io.github.rkaushikdev.hitchline.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.domain.ForbiddenException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.http.Auth.Principal;
import io.github.rkaushikdev.hitchline.http.Auth.Scope;
import io.github.rkaushikdev.hitchline.infra.Clocks;
import io.github.rkaushikdev.hitchline.infra.IdSources;
import io.github.rkaushikdev.hitchline.infra.store.MemoryStore;
import io.github.rkaushikdev.hitchline.support.Inject;
import io.github.rkaushikdev.hitchline.support.Inject.Reply;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

class AuthTest {

    private static final String ADA = Ids.crewMemberId("mem_0123456789ABCDEF");
    private static final String BO = Ids.crewMemberId("mem_FEDCBA9876543210");

    private static final ApiToken ADA_TOKEN = new ApiToken("ada-token-0123456789", ADA, List.of("read", "write"));
    private static final ApiToken ADMIN_TOKEN = new ApiToken("admin-token-0123456", BO, List.of("admin"));

    /** Routes guarded by each kind of check. */
    @RestController
    static class GuardedRoutes {

        @GetMapping("/needs-read")
        public Map<String, String> needsRead(HttpServletRequest request) {
            return Map.of("who", Auth.requireScope(request, Scope.READ).memberId());
        }

        @GetMapping("/needs-write")
        public Map<String, String> needsWrite(HttpServletRequest request) {
            return Map.of("who", Auth.requireScope(request, Scope.WRITE).memberId());
        }

        @GetMapping("/needs-admin")
        public Map<String, String> needsAdmin(HttpServletRequest request) {
            return Map.of("who", Auth.requireScope(request, Scope.ADMIN).memberId());
        }

        @GetMapping("/for/{memberId}")
        public Map<String, String> forMember(@PathVariable("memberId") String memberId, HttpServletRequest request) {
            return Map.of(
                    "who",
                    Auth.requireSelfOrAdmin(request, Ids.crewMemberId(memberId)).memberId());
        }
    }

    @Nested
    class ReadingTheHeader {

        @Test
        void takesABearerToken() {
            assertThat(Auth.readBearer("Bearer abc123")).hasValue("abc123");
            assertThat(Auth.readBearer("bearer abc123")).hasValue("abc123");
        }

        @Test
        void ignoresAnythingElse() {
            assertThat(Auth.readBearer(null)).isEmpty();
            assertThat(Auth.readBearer("Basic abc123")).isEmpty();
            assertThat(Auth.readBearer("abc123")).isEmpty();
            assertThat(Auth.readBearer("Bearer ")).isEmpty();
        }
    }

    @Nested
    class Scopes {

        @Test
        void knowsTheThreeItUses() {
            assertThat(Auth.isScope("write")).isTrue();
            assertThat(Auth.isScope("root")).isFalse();
        }

        @Test
        void buildsAPrincipalFromAToken() {
            assertThat(Auth.principalOf(ADA_TOKEN)).isEqualTo(new Principal(ADA, List.of(Scope.READ, Scope.WRITE)));
        }

        @Test
        void refusesATokenWithNoScopeItUnderstands() {
            ApiToken root = new ApiToken(ADA_TOKEN.token(), ADA, List.of("root"));
            assertThatThrownBy(() -> Auth.principalOf(root)).isInstanceOf(ValidationException.class);
        }

        @Test
        void letsAdminStandInForAnything() {
            Principal admin = Auth.principalOf(ADMIN_TOKEN);
            assertThat(Auth.hasScope(admin, Scope.READ)).isTrue();
            assertThat(Auth.hasScope(admin, Scope.WRITE)).isTrue();
            assertThat(Auth.hasScope(Auth.principalOf(ADA_TOKEN), Scope.ADMIN)).isFalse();
        }
    }

    @Nested
    class FindingAPrincipal {

        private final List<ApiToken> tokens = List.of(ADA_TOKEN, ADMIN_TOKEN);

        @Test
        void matchesAKnownToken() {
            assertThat(Auth.findPrincipal(tokens, "Bearer ada-token-0123456789").map(Principal::memberId))
                    .hasValue(ADA);
        }

        @Test
        void answersNothingForAnUnknownOne() {
            assertThat(Auth.findPrincipal(tokens, "Bearer nope")).isEmpty();
            assertThat(Auth.findPrincipal(tokens, null)).isEmpty();
            assertThat(Auth.findPrincipal(List.of(), "Bearer ada-token-0123456789"))
                    .isEmpty();
        }
    }

    @Nested
    class GuardingARequest {

        private Inject app;

        @BeforeEach
        void build() {
            HttpApp built = HttpServer.build(
                    HttpServerTest.CONFIG,
                    MemoryStore.create(Clocks.fixedClock("2025-02-10T09:00:00Z"), IdSources.randomIds()));
            Auth.register(built, List.of(ADA_TOKEN, ADMIN_TOKEN));
            built.addRoutes(new GuardedRoutes());
            app = Inject.into(built);
        }

        private Reply get(String url) {
            return app.get(url);
        }

        private Reply get(String url, String token) {
            return app.call("GET", url, token);
        }

        @Test
        void turnsARequestWithNoTokenAway() {
            Reply response = get("/needs-read");
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.json().get("message").asText()).contains("needs a bearer token");
        }

        @Test
        void letsATokenWithTheScopeThrough() {
            Reply response = get("/needs-read", ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.json().get("who").asText()).isEqualTo(ADA);
        }

        @Test
        void turnsAwayATokenWithoutTheScope() {
            Reply response = get("/needs-admin", ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.json().get("message").asText()).contains("admin scope");
        }

        @Test
        void letsAnAdminTokenThroughAnywhere() {
            assertThat(get("/needs-write", ADMIN_TOKEN.token()).status()).isEqualTo(200);
        }

        @Test
        void letsSomebodyActForThemselves() {
            assertThat(get("/for/" + ADA, ADA_TOKEN.token()).status()).isEqualTo(200);
        }

        @Test
        void stopsSomebodyActingForAnotherMember() {
            Reply response = get("/for/" + BO, ADA_TOKEN.token());
            assertThat(response.status()).isEqualTo(403);
            assertThat(response.json().get("message").asText()).contains("may not act for");
        }

        @Test
        void letsAnAdminActForAnybody() {
            assertThat(get("/for/" + ADA, ADMIN_TOKEN.token()).status()).isEqualTo(200);
        }
    }

    @Nested
    class RequireScopeOnItsOwn {

        @Test
        void throwsWithoutAPrincipal() {
            assertThatThrownBy(() -> Auth.requireScope(new MockHttpServletRequest(), Scope.READ))
                    .isInstanceOf(ForbiddenException.class);
        }
    }
}
