package com.ssk.erp.config;

import com.ssk.erp.dto.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 로그인 확인
 * - /login, /signup, /error : 누구나
 * - /internal/**   : Python AI 서비스 전용 (토큰은 InternalApiController 가 확인).
 *                    Cloudflare(공개 주소)를 거쳐 들어온 요청은 여기서 바로 차단
 * - /api/**        : 로그인 안 했으면 401 JSON
 * - /users/**      : 관리자(is_admin = 1)만
 * - 나머지 화면     : 로그인 안 했으면 /login 으로
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_KEY = "loginUser";

    public static LoginUser current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object u = session.getAttribute(SESSION_KEY);
        return u instanceof LoginUser lu ? lu : null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String uri = request.getRequestURI();

        if (uri.startsWith("/internal/")) {
            if (request.getHeader("CF-Connecting-IP") != null) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            return true;
        }
        if (uri.equals("/login") || uri.equals("/signup") || uri.startsWith("/error")) {
            return true;
        }

        LoginUser user = current(request);
        if (user == null) {
            if (uri.startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.getWriter().write("{\"error\":\"로그인이 필요합니다. 새로고침 후 다시 로그인해 주세요.\"}");
                return false;
            }
            response.sendRedirect("/login");
            return false;
        }
        if (uri.startsWith("/users") && !user.admin()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
