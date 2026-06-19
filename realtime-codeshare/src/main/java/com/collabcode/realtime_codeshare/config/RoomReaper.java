package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.controller.OTWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodically sweeps rooms that are empty and idle past the configured TTL.
 * Eviction normally happens eagerly when the last client disconnects; this is
 * the backstop that reclaims anything left behind.
 */
@Component
public class RoomReaper {

    private static final Logger log = LoggerFactory.getLogger(RoomReaper.class);

    private final OTWebSocketHandler handler;
    private final long idleTtlMs;

    public RoomReaper(OTWebSocketHandler handler,
                      @Value("${codeshare.room.idle-ttl-ms:1800000}") long idleTtlMs) {
        this.handler = handler;
        this.idleTtlMs = idleTtlMs;
    }

    @Scheduled(fixedDelayString = "${codeshare.reaper.interval-ms:60000}")
    public void sweep() {
        List<String> reaped = handler.reapIdleRooms(idleTtlMs, System.currentTimeMillis());
        if (!reaped.isEmpty()) {
            log.info("Reaper evicted {} idle room(s): {}", reaped.size(), reaped);
        }
    }
}
