package com.mmagym.bot.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    public record Session(String action, Map<String, String> data) {}

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public void put(String phone, String action, Map<String, String> data) {
        sessions.put(phone, new Session(action, new ConcurrentHashMap<>(data)));
    }

    public void put(String phone, String action) {
        put(phone, action, Map.of());
    }

    public Session get(String phone) {
        return sessions.get(phone);
    }

    public boolean has(String phone) {
        return sessions.containsKey(phone);
    }

    public void clear(String phone) {
        sessions.remove(phone);
    }

    public void updateData(String phone, String key, String value) {
        Session s = sessions.get(phone);
        if (s != null) s.data().put(key, value);
    }
}
