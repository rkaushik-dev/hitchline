package io.github.rkaushikdev.hitchline.config;

import java.util.List;

/**
 * A bearer token the api accepts.
 *
 * @param token the secret, 16 to 128 letters, digits, hyphens and underscores
 * @param memberId the member the token acts for
 * @param scopes what it may do, such as {@code read}, {@code write} or {@code admin}
 */
public record ApiToken(String token, String memberId, List<String> scopes) {

    public ApiToken {
        scopes = List.copyOf(scopes);
    }
}
