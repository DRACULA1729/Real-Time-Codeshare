package com.collabcode.realtime_codeshare.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Connection tickets are single-use and bind {sessionId, token, clientId}. A
 * leaked or replayed ticket must be useless on the second presentation.
 */
class InMemoryTicketStoreTest {

    private final InMemoryTicketStore store = new InMemoryTicketStore();

    @Test
    void issueThenRedeemReturnsTheBinding() {
        String id = store.issue("sess-1", "tok-abc", "client-7");
        Ticket t = store.redeem(id);
        assertThat(t).isNotNull();
        assertThat(t.sessionId()).isEqualTo("sess-1");
        assertThat(t.token()).isEqualTo("tok-abc");
        assertThat(t.clientId()).isEqualTo("client-7");
    }

    @Test
    void redemptionIsSingleUse() {
        String id = store.issue("sess-1", "tok-abc", "client-7");
        assertThat(store.redeem(id)).isNotNull();
        assertThat(store.redeem(id)).isNull(); // already consumed
    }

    @Test
    void unknownOrBlankTicketsRedeemToNull() {
        assertThat(store.redeem("never-issued")).isNull();
        assertThat(store.redeem(null)).isNull();
        assertThat(store.redeem("")).isNull();
    }

    @Test
    void nullTokenIsAllowedForBrandNewJoiners() {
        String id = store.issue("sess-1", null, "client-7");
        Ticket t = store.redeem(id);
        assertThat(t).isNotNull();
        assertThat(t.token()).isNull();
    }
}
