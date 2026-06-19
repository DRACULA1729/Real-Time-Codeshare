package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.controller.OTWebSocketHandler;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.collabcode.realtime_codeshare.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically reclaims rooms that are empty and idle past the configured TTL.
 * Sessions are kept alive after their last client leaves so approved users can
 * reconnect without re-approval; this sweep removes the ones that never come back.
 */
@Component
public class RoomReaper {

    private static final Logger log = LoggerFactory.getLogger(RoomReaper.class);

    private final OTWebSocketHandler handler;
    private final SessionService sessionService;
    private final OTService otService;
    private final long idleTtlMs;

    public RoomReaper(OTWebSocketHandler handler,
                      SessionService sessionService,
                      OTService otService,
                      @Value("${codeshare.room.idle-ttl-ms:1800000}") long idleTtlMs) {
        this.handler = handler;
        this.sessionService = sessionService;
        this.otService = otService;
        this.idleTtlMs = idleTtlMs;
    }

    @Scheduled(fixedDelayString = "${codeshare.reaper.interval-ms:60000}")
    public void sweep() {
        long now = System.currentTimeMillis();
        handler.reapIdleRooms(idleTtlMs, now);                 // OT/socket backstop
        List<String> reaped = sessionService.reapIdle(idleTtlMs, now);
        for (String id : reaped) {
            otService.evict(id);
        }
        if (!reaped.isEmpty()) {
            log.info("Reaper reclaimed {} idle session(s): {}", reaped.size(), reaped);
        }
    }
}
