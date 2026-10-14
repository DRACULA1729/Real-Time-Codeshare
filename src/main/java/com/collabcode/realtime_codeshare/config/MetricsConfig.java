package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.controller.OTWebSocketHandler;
import com.collabcode.realtime_codeshare.ot.OTService;
import com.collabcode.realtime_codeshare.service.SessionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Registers live operational gauges with Micrometer (exported via /actuator/prometheus):
 * active access-control sessions, resident document rooms, and admitted WebSocket
 * connections. Counters (committed ops, rate-limit drops, broadcast send failures)
 * are incremented inline in {@link OTWebSocketHandler}.
 */
@Configuration
public class MetricsConfig {

    public MetricsConfig(MeterRegistry registry,
                         SessionService sessionService,
                         OTService otService,
                         OTWebSocketHandler handler) {
        Gauge.builder("codeshare.sessions.active", sessionService, SessionService::count)
                .description("Live access-control sessions")
                .register(registry);
        Gauge.builder("codeshare.rooms.live", otService, OTService::roomCount)
                .description("Resident document stores")
                .register(registry);
        Gauge.builder("codeshare.ws.connections", handler, OTWebSocketHandler::connectionCount)
                .description("Admitted WebSocket connections")
                .register(registry);
    }
}
