package com.muyulu.aisimtrans.service;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.subtitle.SubtitleEvent;
import com.muyulu.aisimtrans.subtitle.SubtitleEventPublisher;

@Component
public class SubtitleWebSocketHandler extends TextWebSocketHandler implements SubtitleEventPublisher {
    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public SubtitleWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void publish(SubtitleEvent event) {
        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(event));
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    send(session, message);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize subtitle event.", ex);
        }
    }

    private void send(WebSocketSession session, TextMessage message) {
        synchronized (session) {
            try {
                session.sendMessage(message);
            } catch (IOException ex) {
                sessions.remove(session);
            }
        }
    }
}
