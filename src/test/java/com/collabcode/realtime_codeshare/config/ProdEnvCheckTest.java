package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.github.GitHubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** prod must not boot on a store that loses documents on restart. */
class ProdEnvCheckTest {

    private ProdEnvCheck checkFor(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return new ProdEnvCheck("https://codeshare.example.com", new GitHubProperties(), env);
    }

    @Test
    void prodWithoutPostgresFailsFast() {
        assertThatThrownBy(() -> checkFor("prod").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'postgres' profile");
        assertThatThrownBy(() -> checkFor("prod", "redis").afterPropertiesSet())
                .hasMessageContaining("'postgres' profile");
    }

    @Test
    void prodWithPostgresPasses() {
        assertThatCode(() -> checkFor("prod", "postgres").afterPropertiesSet()).doesNotThrowAnyException();
    }
}
