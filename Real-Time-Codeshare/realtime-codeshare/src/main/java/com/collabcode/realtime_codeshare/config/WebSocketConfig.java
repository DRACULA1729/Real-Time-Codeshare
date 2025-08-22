package com.collabcode.realtime_codeshare.config;

import com.collabcode.realtime_codeshare.controller.OTWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OTWebSocketHandler otWebSocketHandler;

    public WebSocketConfig(OTWebSocketHandler otWebSocketHandler) {
        this.otWebSocketHandler = otWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(otWebSocketHandler, "/ws/ot").setAllowedOrigins("*");
    }
}

