package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.controller.OTWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableScheduling
public class WebSocketConfig implements WebSocketConfigurer {

    private final OTWebSocketHandler otWebSocketHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;
    private final String[] allowedOrigins;
    private final int maxMessageBytes;

    public WebSocketConfig(OTWebSocketHandler otWebSocketHandler,
                           WsHandshakeInterceptor handshakeInterceptor,
                           @Value("${codeshare.allowed-origins:http://localhost:8080}") String allowedOrigins,
                           @Value("${codeshare.ws.max-message-bytes:65536}") int maxMessageBytes) {
        this.otWebSocketHandler = otWebSocketHandler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.allowedOrigins = splitOrigins(allowedOrigins);
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(otWebSocketHandler, "/ws/ot")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }

    /** Cap the inbound text/binary frame size so a single huge message can't exhaust memory. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxMessageBytes);
        container.setMaxBinaryMessageBufferSize(maxMessageBytes);
        return container;
    }

    private static String[] splitOrigins(String csv) {
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
