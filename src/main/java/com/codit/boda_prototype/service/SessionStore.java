package com.codit.boda_prototype.service;

import com.codit.boda_prototype.model.UserSession;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프로토타입용 인메모리 세션 저장소.
 * 실제 서비스 전환 시 PostgreSQL + Redis로 교체.
 */
@Component
public class SessionStore {

    private final Map<String, UserSession> store = new ConcurrentHashMap<>();

    public UserSession getOrCreate(String sessionId) {
        return store.computeIfAbsent(sessionId, id -> {
            UserSession s = new UserSession();
            s.setSessionId(id);
            s.setCreatedAt(LocalDateTime.now());
            return s;
        });
    }

    public UserSession get(String sessionId) {
        return store.get(sessionId);
    }

    public boolean exists(String sessionId) {
        return sessionId != null && store.containsKey(sessionId);
    }

    public String newSessionId() {
        return UUID.randomUUID().toString();
    }
}
