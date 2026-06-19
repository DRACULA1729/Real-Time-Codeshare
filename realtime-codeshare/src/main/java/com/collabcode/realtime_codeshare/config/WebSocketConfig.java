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
    private final int maxMessageBytes;

    public WebSocketConfig(OTWebSocketHandler otWebSocketHandler,
                           @Value("${codeshare.ws.max-message-bytes:65536}") int maxMessageBytes) {
        this.otWebSocketHandler = otWebSocketHandler;
        this.maxMessageBytes = maxMessageBytes;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(otWebSocketHandler, "/ws/ot");
    }

    /** Cap the inbound text/binary frame size so a single huge message can't exhaust memory. */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxMessageBytes);
        container.setMaxBinaryMessageBufferSize(maxMessageBytes);
        return container;
    }
}
