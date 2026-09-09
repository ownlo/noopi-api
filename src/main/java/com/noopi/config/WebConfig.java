package com.noopi.config;

import com.noopi.realtime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebConfig implements WebSocketConfigurer, WebMvcConfigurer {
    private final RoomSocketHandler handler;
    private final RoomHandshakeInterceptor handshake;
    private final String[] origins;
    public WebConfig(RoomSocketHandler handler, RoomHandshakeInterceptor handshake,
                     @Value("${noopi.allowed-origins:http://localhost:3000}") String[] origins) {
        this.handler = handler; this.handshake = handshake; this.origins = origins;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").addInterceptors(handshake).setAllowedOrigins(origins);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST", "DELETE")
            .allowedHeaders("Content-Type", "X-Client-Id");
    }
}
