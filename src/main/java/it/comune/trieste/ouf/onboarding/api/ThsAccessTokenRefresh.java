package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.onboarding.application.ThsBearerTimeouts;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

final class ThsAccessTokenRefresh {
    private static final Logger LOG = LoggerFactory.getLogger(ThsAccessTokenRefresh.class);
    private static final Duration UNKNOWN_LIFETIME_REMAINING = Duration.ofSeconds(150);
    private static final Duration MINIMUM_OVERLAP = ThsBearerTimeouts.MAX_REQUEST.multipliedBy(3);

    private final Clock clock;
    private final Set<Long> observedLifetimeSeconds = ConcurrentHashMap.newKeySet();

    ThsAccessTokenRefresh(Clock clock) {
        this.clock = clock;
    }

    boolean expired(OAuth2AccessToken token) {
        var expiry = token.getExpiresAt();
        return expiry != null && !clock.instant().isBefore(expiry);
    }

    boolean refreshDue(OAuth2AccessToken token) {
        var expiry = token.getExpiresAt();
        if (expiry == null) return false;
        var now = clock.instant();
        var issued = token.getIssuedAt();
        if (issued == null) {
            return Duration.between(now, expiry).compareTo(UNKNOWN_LIFETIME_REMAINING) < 0;
        }
        var halfLife = Duration.between(issued, expiry).dividedBy(2);
        var refreshAt = issued.plus(halfLife);
        if (halfLife.compareTo(MINIMUM_OVERLAP) < 0) {
            var latest = expiry.minus(MINIMUM_OVERLAP);
            if (latest.isBefore(refreshAt)) refreshAt = latest.isBefore(issued) ? issued : latest;
        }
        return !now.isBefore(refreshAt);
    }

    OAuth2AuthorizedClient observeIssuance(OAuth2AuthorizedClient client) {
        var token = client.getAccessToken();
        var expiry = token.getExpiresAt();
        if (expiry == null) return client;
        var issued = token.getIssuedAt();
        if (issued == null) {
            var observedLifetime = Duration.between(clock.instant(), expiry);
            if (observedLifetime.isNegative() || observedLifetime.isZero()) return client;
            issued = expiry.minus(observedLifetime);
        }
        var lifetime = Duration.between(issued, expiry);
        long seconds = lifetime.toSeconds();
        if (observedLifetimeSeconds.add(seconds)) {
            LOG.info("THS_ACCESS_TOKEN_LIFETIME_OBSERVED lifetimeSeconds={}", seconds);
            var halfLife = lifetime.dividedBy(2);
            if (halfLife.compareTo(MINIMUM_OVERLAP) < 0) {
                LOG.warn("THS_ACCESS_TOKEN_HALF_LIFE_TOO_SHORT lifetimeSeconds={} halfLifeSeconds={} minimumSeconds={}",
                        seconds, halfLife.toSeconds(), MINIMUM_OVERLAP.toSeconds());
            }
        }
        if (token.getIssuedAt() != null) return client;
        var observed = new OAuth2AccessToken(token.getTokenType(), token.getTokenValue(), issued, expiry,
                token.getScopes());
        return new OAuth2AuthorizedClient(client.getClientRegistration(), client.getPrincipalName(),
                observed, client.getRefreshToken());
    }
}
