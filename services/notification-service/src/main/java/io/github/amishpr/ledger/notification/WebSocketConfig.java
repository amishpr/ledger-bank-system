package io.github.amishpr.ledger.notification;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** A plain WebSocket at /ws, no STOMP, because that is what the dashboard speaks. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfig implements WebSocketConfigurer {

    private final LedgerSocketHandler handler;
    private final NotificationProperties properties;

    WebSocketConfig(LedgerSocketHandler handler, NotificationProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").setAllowedOriginPatterns(properties.allowedOrigins().toArray(String[]::new));
    }
}
