package com.collabcode.realtime_codeshare.ticket;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default single-node {@link TicketStore}. Tickets live ~30s, are removed on
 * first redemption (single-use), and a periodic sweep drops any that were minted
 * but never redeemed so unredeemed tickets can't accumulate.
 */
@Component
public class InMemoryTicketStore implements TicketStore {

    /** Short window: enough to open the socket right after minting, useless if logged/leaked. */
    static final long TTL_MS = 30_000;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> tickets = new ConcurrentHashMap<>();

    private record Entry(Ticket ticket, long expiresAtMs) {}

    @Override
    public String issue(String sessionId, String token, String clientId) {
        String id = randomTicket();
        tickets.put(id, new Entry(new Ticket(sessionId, token, clientId), now() + TTL_MS));
        return id;
    }

    @Override
    public Ticket redeem(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) return null;
        Entry e = tickets.remove(ticketId); // single-use: gone after the first redemption
        if (e == null || e.expiresAtMs() < now()) return null;
        return e.ticket();
    }

    /** Bound memory: clear tickets that were minted but never redeemed before expiry. */
    @Scheduled(fixedDelay = TTL_MS)
    void sweepExpired() {
        long now = now();
        tickets.values().removeIf(e -> e.expiresAtMs() < now);
    }

    private String randomTicket() {
        byte[] b = new byte[24];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
