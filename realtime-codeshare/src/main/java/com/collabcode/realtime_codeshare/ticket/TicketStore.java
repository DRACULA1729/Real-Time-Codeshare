package com.collabcode.realtime_codeshare.ticket;

/**
 * Issues and redeems single-use, short-TTL connection tickets so the session
 * token never has to ride in the WebSocket URL (CWE-598). The client mints a
 * ticket over an authenticated HTTPS POST, then presents only the opaque ticket
 * in the upgrade query string; the interceptor redeems it back to the real
 * binding. A leaked ticket is useless once expired or consumed.
 *
 * <p>The in-memory implementation ({@link InMemoryTicketStore}) is the default;
 * a Redis-backed implementation can be swapped in for multi-node deployments
 * without touching the controller or interceptor.
 */
public interface TicketStore {

    /**
     * Mint a ticket bound to a pending connection.
     *
     * @param sessionId the room the ticket admits to
     * @param token     the member/admin token (may be {@code null} for a brand-new
     *                  joiner who will be minted one server-side at admission)
     * @param clientId  the tab-local client id
     * @return the opaque ticket id to carry in the WS upgrade query string
     */
    String issue(String sessionId, String token, String clientId);

    /**
     * Atomically consume a ticket. Returns the binding exactly once; a second
     * redemption, an expired ticket, or an unknown id all return {@code null}.
     */
    Ticket redeem(String ticketId);
}
