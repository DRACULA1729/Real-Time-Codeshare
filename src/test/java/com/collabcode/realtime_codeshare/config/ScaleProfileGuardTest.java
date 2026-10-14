package com.collabcode.realtime_codeshare.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The postgres and redis profiles are mutually exclusive until the composite repo exists. */
class ScaleProfileGuardTest {

    private ScaleProfileGuard guardFor(String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        ScaleProfileGuard guard = new ScaleProfileGuard();
        guard.setEnvironment(env);
        return guard;
    }

    @Test
    void bothProfilesActiveFailsFast() {
        assertThatThrownBy(() -> guardFor("postgres", "redis").postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    @Test
    void singleScaleProfileIsAllowed() {
        assertThatCode(() -> guardFor("postgres").postProcessBeanFactory(null)).doesNotThrowAnyException();
        assertThatCode(() -> guardFor("redis").postProcessBeanFactory(null)).doesNotThrowAnyException();
    }

    @Test
    void defaultNoProfilesIsAllowed() {
        assertThatCode(() -> guardFor().postProcessBeanFactory(null)).doesNotThrowAnyException();
    }
}
