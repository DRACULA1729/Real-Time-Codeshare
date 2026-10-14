package com.collabcode.realtime_codeshare.ticket;

/**
 * The binding recovered when a connection ticket is redeemed: which room, which
 * (possibly null) member token, and which client id the ticket was minted for.
 */
public record Ticket(String sessionId, String token, String clientId) {}
