package com.mmagym.bot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session store for multi-step WhatsApp flows.
 * Sessions expire after 10 minutes of inactivity — prevents permanently stuck sessions
 * when a user abandons a flow mid-way.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final long TTL_SECONDS = 600; // 10 minutes

    public record Session(String action, Map<String, String> data, Instant createdAt) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final BotMetrics metrics;

    public SessionService(BotMetrics metrics) {
        this.metrics = metrics;
    }

    public void put(String phone, String action, Map<String, String> data) {
        boolean isNew = !sessions.containsKey(phone);
        sessions.put(phone, new Session(action, new ConcurrentHashMap<>(data), Instant.now()));
        if (isNew) metrics.sessionOpened();
    }

    public void put(String phone, String action) {
        put(phone, action, Map.of());
    }

    public Session get(String phone) {
        Session s = sessions.get(phone);
        if (s == null) return null;
        // Lazy expiry check on access
        if (isExpired(s)) {
            sessions.remove(phone);
            metrics.sessionClosed();
            log.debug("Session expired for phone={}", phone);
            return null;
        }
        return s;
    }

    public boolean has(String phone) {
        return get(phone) != null;
    }

    public void clear(String phone) {
        Session removed = sessions.remove(phone);
        if (removed != null) metrics.sessionClosed();
    }

    public void updateData(String phone, String key, String value) {
        Session s = sessions.get(phone);
        if (s != null) s.data().put(key, value);
    }

    /** Scheduled cleanup — removes expired sessions every 5 minutes. */
    @Scheduled(fixedDelay = 300_000)
    public void evictExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> {
            if (isExpired(e.getValue())) {
                metrics.sessionClosed();
                return true;
            }
            return false;
        });
        int removed = before - sessions.size();
        if (removed > 0) log.info("Evicted {} expired sessions", removed);
    }

    private boolean isExpired(Session s) {
        return Instant.now().getEpochSecond() - s.createdAt().getEpochSecond() > TTL_SECONDS;
    }
}
