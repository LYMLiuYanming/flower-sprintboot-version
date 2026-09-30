package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.model.UserSession;
import org.liuym.flowerv1springboot.repository.UserSessionRepository;
import org.liuym.flowerv1springboot.service.SessionService;
import org.liuym.flowerv1springboot.vo.SupportViews.SessionView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SessionServiceImpl implements SessionService {

    /** User-Agent 存库前的截断长度，防止超长 UA 撑爆列 */
    private static final int UA_MAX = 255;

    private final UserSessionRepository sessionRepository;

    public SessionServiceImpl(UserSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public UUID recordLogin(UUID userId, String httpSessionId, String ip, String userAgent, boolean rememberMe) {
        LocalDateTime now = LocalDateTime.now();
        UserSession session = new UserSession();
        session.setUserId(userId);
        session.setHttpSessionId(truncate(httpSessionId, 80));
        session.setClientIp(truncate(ip, 64));
        session.setUserAgent(truncate(userAgent, UA_MAX));
        session.setRememberMe(rememberMe);
        session.setRevoked(false);
        session.setCreatedAt(now);
        session.setLastAccessTime(now);
        return sessionRepository.save(session).getToken();
    }

    @Override
    public void touch(UUID token) {
        if (token != null) {
            sessionRepository.touch(token, LocalDateTime.now());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionView> listDevices(UUID userId, UUID currentToken) {
        return sessionRepository.findActiveByUserId(userId).stream()
                .map(s -> SessionView.from(s, currentToken != null && currentToken.equals(s.getToken())))
                .toList();
    }

    @Override
    public int revokeOthers(UUID userId, UUID currentToken) {
        if (currentToken == null) {
            return sessionRepository.revokeAll(userId, LocalDateTime.now());
        }
        return sessionRepository.revokeOthers(userId, currentToken, LocalDateTime.now());
    }

    @Override
    public void revoke(UUID token) {
        if (token == null) {
            return;
        }
        sessionRepository.findById(token).ifPresent(s -> {
            s.setRevoked(true);
            s.setLastAccessTime(LocalDateTime.now());
            sessionRepository.save(s);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID token) {
        return token != null && sessionRepository.findById(token)
                .map(s -> Boolean.FALSE.equals(s.getRevoked()))
                .orElse(false);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
