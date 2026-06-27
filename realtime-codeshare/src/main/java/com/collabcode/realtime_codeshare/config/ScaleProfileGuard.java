package com.collabcode.realtime_codeshare.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * The {@code postgres} and {@code redis} profiles are mutually exclusive in this
 * build: the composite Redis-over-Postgres repository (Redis live cache in front of
 * the durable Postgres store) is still future work, and the two profiles' auto-config
 * exclusions don't merge. Fail fast with a clear message rather than a confusing
 * missing-bean error. Runs as a BeanFactoryPostProcessor so it trips before any
 * repository bean is instantiated.
 */
@Component
public class ScaleProfileGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.contains("postgres") && active.contains("redis")) {
            throw new IllegalStateException(
                    "The 'postgres' and 'redis' profiles are mutually exclusive in this build: "
                    + "the composite Redis-over-Postgres document repository is not implemented yet. "
                    + "Activate exactly one (Neon -> postgres, Upstash -> redis).");
        }
    }
}
