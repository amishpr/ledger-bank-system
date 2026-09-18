package io.github.amishpr.ledger.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds every open dashboard socket and broadcasts to all of them. A raw
 * session is not safe to send on from several threads at once, so each is
 * wrapped in a decorator that serialises sends and drops a client that falls
 * too far behind, rather than letting one slow browser hold up the rest.
 */
@Component
class LedgerSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final JsonMapper jsonMapper;

    LedgerSocketHandler(JsonMapper jsonMapper, MeterRegistry meters) {
        this.jsonMapper = jsonMapper;
        Gauge.builder("notification.websocket.sessions", sessions, Map::size)
                .description("Dashboards currently connected to this instance")
                .register(meters);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        sessions.put(session.getId(), safe);
        send(safe, jsonMapper.writeValueAsString(BrowserMessages.CONNECTED));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // The socket only flows from server to browser. Anything a client sends is ignored.
    }

    void broadcast(Object message) {
        if (sessions.isEmpty()) {
            return;
        }
        String json = jsonMapper.writeValueAsString(message);
        sessions.values().forEach(session -> send(session, json));
    }

    int connectionCount() {
        return sessions.size();
    }

    private void send(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException | RuntimeException e) {
            log.debug("Dropping socket {} after a failed send: {}", session.getId(), e.getMessage());
            sessions.remove(session.getId());
            try {
                session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}
