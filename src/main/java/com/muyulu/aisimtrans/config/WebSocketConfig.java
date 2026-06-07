package com.muyulu.aisimtrans.config;

import com.muyulu.aisimtrans.service.SubtitleWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SubtitleWebSocketHandler subtitleWebSocketHandler;

    public WebSocketConfig(SubtitleWebSocketHandler subtitleWebSocketHandler) {
        this.subtitleWebSocketHandler = subtitleWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(subtitleWebSocketHandler, "/ws/subtitles").setAllowedOrigins("*");
    }
}
