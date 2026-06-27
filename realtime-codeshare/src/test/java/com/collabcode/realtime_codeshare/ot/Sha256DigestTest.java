package com.collabcode.realtime_codeshare.ot;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link OTService#sha256Hex} to the FIPS 180-4 known-answer vectors. These
 * are the exact strings asserted by the TS sha256Hex test, so the client and
 * server document digests are guaranteed identical — drift detection on the
 * resync exchange compares like with like.
 */
class Sha256DigestTest {

    @Test
    void matchesKnownVectors() {
        assertThat(OTService.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(OTService.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(OTService.sha256Hex("hello"))
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void matchesForMultibyteUtf8() {
        // Must encode the same bytes as the browser's TextEncoder (UTF-8).
        assertThat(OTService.sha256Hex("héllo→€"))
                .isEqualTo("64a6ddc26703e014800f40bfa72279d5ccfcfebff26545b93ed1403ebb5ab057");
    }

    @Test
    void nullIsTreatedAsEmpty() {
        assertThat(OTService.sha256Hex(null)).isEqualTo(OTService.sha256Hex(""));
    }
}
