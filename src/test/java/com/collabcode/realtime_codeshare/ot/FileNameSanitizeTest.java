package com.collabcode.realtime_codeshare.ot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-side base-name sanitization for the shared rename feature. The rules
 * (dot-cut, strip control chars and path separators, trim, 64-char cap) must stay
 * identical to the client's sanitizeBaseName in frontend/src/lib/fileName.ts —
 * frontend/src/lib/fileName.test.ts pins the same case matrix.
 */
class FileNameSanitizeTest {

    @Test
    void nullAndBlankBecomeEmpty() {
        assertThat(OTService.sanitizeBaseName(null)).isEmpty();
        assertThat(OTService.sanitizeBaseName("")).isEmpty();
        assertThat(OTService.sanitizeBaseName("   ")).isEmpty();
    }

    @Test
    void plainNamePassesThrough() {
        assertThat(OTService.sanitizeBaseName("MyClass")).isEqualTo("MyClass");
    }

    @Test
    void everythingFromTheFirstDotIsCut() {
        assertThat(OTService.sanitizeBaseName("my.file.java")).isEqualTo("my");
        assertThat(OTService.sanitizeBaseName("../evil")).isEmpty();
        assertThat(OTService.sanitizeBaseName(".hidden")).isEmpty();
    }

    @Test
    void pathSeparatorsAndControlCharsAreStripped() {
        assertThat(OTService.sanitizeBaseName("a/b\\c")).isEqualTo("abc");
        assertThat(OTService.sanitizeBaseName("a\u0001bcd\ne")).isEqualTo("abcde");
    }

    @Test
    void surroundingWhitespaceIsTrimmedAfterStripping() {
        assertThat(OTService.sanitizeBaseName("  my file  ")).isEqualTo("my file");
        assertThat(OTService.sanitizeBaseName("  my.file.java  ")).isEqualTo("my");
    }

    @Test
    void lengthIsCappedAt64() {
        String long65 = "x".repeat(65);
        assertThat(OTService.sanitizeBaseName(long65)).hasSize(OTService.MAX_BASENAME_LEN);
    }
}
