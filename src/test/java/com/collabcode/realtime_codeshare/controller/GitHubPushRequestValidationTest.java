package com.collabcode.realtime_codeshare.controller;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The push path is the GitHub path-injection surface. Its @Pattern must reject
 * absolute paths and any traversal, and @NotBlank must catch missing fields.
 */
class GitHubPushRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setup() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    private GitHubController.PushRequest req(String path) {
        GitHubController.PushRequest r = new GitHubController.PushRequest();
        r.owner = "octocat";
        r.repo = "hello";
        r.path = path;
        r.sessionId = "sess";
        r.sessionToken = "tok";
        return r;
    }

    @Test
    void acceptsANormalRelativePath() {
        assertThat(validator.validate(req("src/Main.java"))).isEmpty();
        assertThat(validator.validate(req("a/b/c.txt"))).isEmpty();
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThat(validator.validate(req("/etc/passwd"))).isNotEmpty();
    }

    @Test
    void rejectsParentTraversal() {
        assertThat(validator.validate(req("../secret"))).isNotEmpty();
        assertThat(validator.validate(req("a/../../b"))).isNotEmpty();
    }

    @Test
    void rejectsMissingRequiredFields() {
        GitHubController.PushRequest r = req("ok.txt");
        r.owner = null;
        assertThat(validator.validate(r)).isNotEmpty();
        r.owner = "octocat";
        r.sessionToken = "  ";
        assertThat(validator.validate(r)).isNotEmpty();
    }
}
