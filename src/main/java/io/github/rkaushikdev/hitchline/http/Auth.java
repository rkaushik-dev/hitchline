package io.github.rkaushikdev.hitchline.http;

import io.github.rkaushikdev.hitchline.config.ApiToken;
import io.github.rkaushikdev.hitchline.domain.ForbiddenException;
import io.github.rkaushikdev.hitchline.domain.Ids;
import io.github.rkaushikdev.hitchline.domain.ValidationException;
import io.github.rkaushikdev.hitchline.util.Texts;
import io.github.rkaushikdev.hitchline.util.WireValue;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Bearer tokens, checked against the list the process was started with. There is no user table and
 * no password anywhere in this codebase on purpose: the operator already has an identity provider
 * and this is the seam into it.
 */
public final class Auth {

    /** The request attribute holding the caller, when the request carried a known token. */
    public static final String PRINCIPAL = Auth.class.getName() + ".principal";

    /** What a token may do. Admin stands in for the other two. */
    public enum Scope implements WireValue {
        READ("read"),
        WRITE("write"),
        ADMIN("admin");

        private final String wire;

        Scope(String wire) {
            this.wire = wire;
        }

        @Override
        public String wire() {
            return wire;
        }

        @Override
        public String toString() {
            return wire;
        }

        public static Optional<Scope> fromWire(String wire) {
            return WireValue.fromWire(Scope.class, wire);
        }
    }

    /**
     * Who is calling.
     *
     * @param memberId the crew member the token acts as
     * @param scopes what the token may do
     */
    public record Principal(String memberId, List<Scope> scopes) {

        public Principal {
            Objects.requireNonNull(memberId, "memberId");
            scopes = List.copyOf(scopes);
        }
    }

    private Auth() {}

    /** True for a scope word this build knows. */
    public static boolean isScope(String value) {
        return Scope.fromWire(value).isPresent();
    }

    /** The caller a token stands for, keeping only the scopes this build knows. */
    public static Principal principalOf(ApiToken token) {
        List<Scope> scopes = token.scopes().stream()
                .map(Scope::fromWire)
                .flatMap(Optional::stream)
                .toList();
        if (scopes.isEmpty()) {
            throw ValidationException.field(
                    "scopes", "names none this build knows: " + String.join("|", token.scopes()));
        }
        return new Principal(Ids.crewMemberId(token.memberId()), scopes);
    }

    /** The token in an {@code Authorization: Bearer <token>} header, and nothing else. */
    public static Optional<String> readBearer(String header) {
        if (header == null) {
            return Optional.empty();
        }
        List<String> parts = Texts.split(header, " ");
        if (parts.size() != 2 || !parts.get(0).toLowerCase(Locale.ROOT).equals("bearer")) {
            return Optional.empty();
        }
        String token = parts.get(1);
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /** The caller whose token the header presents, if it is one of {@code tokens}. */
    public static Optional<Principal> findPrincipal(List<ApiToken> tokens, String header) {
        return readBearer(header).flatMap(presented -> tokens.stream()
                .filter(token -> token.token().equals(presented))
                .findFirst()
                .map(Auth::principalOf));
    }

    /** True when the caller holds {@code scope}, or admin. */
    public static boolean hasScope(Principal principal, Scope scope) {
        return principal.scopes().contains(scope) || principal.scopes().contains(Scope.ADMIN);
    }

    /** The caller of {@code request}, when it carried a known token. */
    public static Optional<Principal> principalOf(HttpServletRequest request) {
        return request.getAttribute(PRINCIPAL) instanceof Principal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /** The caller, refused unless the request carried a token holding {@code scope}. */
    public static Principal requireScope(HttpServletRequest request, Scope scope) {
        Principal principal =
                principalOf(request).orElseThrow(() -> new ForbiddenException("this endpoint needs a bearer token"));
        if (!hasScope(principal, scope)) {
            throw new ForbiddenException("this endpoint needs the " + scope.wire() + " scope");
        }
        return principal;
    }

    /** Only an admin, or the person themselves, may act for a member. */
    public static Principal requireSelfOrAdmin(HttpServletRequest request, String memberId) {
        Principal principal = requireScope(request, Scope.READ);
        if (principal.memberId().equals(memberId) || principal.scopes().contains(Scope.ADMIN)) {
            return principal;
        }
        throw new ForbiddenException(principal.memberId() + " may not act for " + memberId);
    }

    /** Reads the caller of every request from its bearer token. */
    public static void register(HttpApp app, List<ApiToken> tokens) {
        List<ApiToken> known = List.copyOf(tokens);
        app.addRequestHook((request, response) -> {
            findPrincipal(known, request.getHeader("authorization"))
                    .ifPresent(principal -> request.setAttribute(PRINCIPAL, principal));
            return true;
        });
    }
}
