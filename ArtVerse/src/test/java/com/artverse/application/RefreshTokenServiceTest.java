package com.artverse.application;

import com.artverse.application.RefreshTokenService.ConsumptionStatus;
import com.artverse.config.ArtVerseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RefreshTokenService")
class RefreshTokenServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SetOperations<String, String> sets;
    private RefreshTokenService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        sets = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        ArtVerseProperties properties = new ArtVerseProperties();
        properties.getAuth().getCookie().setRefreshTokenTimeoutSeconds(43_200);
        service = new RefreshTokenService(redis, properties);
    }

    @Test
    @DisplayName("issues an indexed token without storing the plaintext token")
    void issuesIndexedToken() {
        String token = service.issue(7L);

        assertThat(token).isNotBlank();
        verify(values).set(startsWith("rt:token:"), eq("7"), eq(Duration.ofHours(12)));
        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        verify(sets).add(eq("rt:user:7"), hashCaptor.capture());
        assertThat(hashCaptor.getValue()).hasSize(64).doesNotContain(token);
        verify(redis).expire("rt:user:7", Duration.ofHours(12));
    }

    @Test
    @DisplayName("consumes a token exactly once and records reuse ownership")
    void consumesToken() {
        when(values.getAndDelete(startsWith("rt:token:"))).thenReturn("7");

        var result = service.consume("refresh-token", null);

        assertThat(result.status()).isEqualTo(ConsumptionStatus.VALID);
        assertThat(result.userId()).isEqualTo(7L);
        verify(values).set(startsWith("rt:used:"), eq("7"), eq(Duration.ofHours(12)));
        verify(sets).remove(eq("rt:user:7"), anyString());
    }

    @Test
    @DisplayName("identifies reuse after the active token has been consumed")
    void identifiesReuse() {
        when(values.getAndDelete(startsWith("rt:token:"))).thenReturn(null);
        when(values.get(startsWith("rt:used:"))).thenReturn("7");

        var result = service.consume("used-token", null);

        assertThat(result.status()).isEqualTo(ConsumptionStatus.REUSED);
        assertThat(result.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("rotates a token from the legacy storage layout")
    void consumesLegacyTokenWhenAccessIdentityIsKnown() {
        when(values.getAndDelete(startsWith("rt:token:"))).thenReturn(null);
        when(redis.delete("rt:7:legacy-token")).thenReturn(true);

        var result = service.consume("legacy-token", 7L);

        assertThat(result.status()).isEqualTo(ConsumptionStatus.VALID);
        assertThat(result.userId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("revokes indexed tokens for one user")
    void revokesAllIndexedTokens() {
        when(sets.members("rt:user:7")).thenReturn(Set.of("hash-a", "hash-b"));
        when(redis.keys("rt:7:*")).thenReturn(Set.of());

        service.revokeAll(7L);

        verify(redis).delete(argThat((Collection<String> keys) ->
                keys.containsAll(Set.of("rt:token:hash-a", "rt:token:hash-b", "rt:user:7"))));
    }
}
