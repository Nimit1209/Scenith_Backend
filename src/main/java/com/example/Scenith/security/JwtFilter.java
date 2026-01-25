package com.example.Scenith.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
@Profile("!test") // Only active in non-test profiles
public class JwtFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;

    public JwtFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Skip filter for OPTIONS requests (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skip filter for public endpoints
        if (path.startsWith("/auth/") ||
                path.startsWith("/api/global-elements") ||
                path.startsWith("/actuator/") ||
                path.startsWith("/login/oauth2/") ||
                path.matches("/projects/\\d+/export") ||
                path.equals("/project/export-links") ||
                path.matches("/projects/\\d+/waveforms/.*") ||
                path.matches("/projects/\\d+/images/.*") ||
                path.matches("/projects/\\d+/audio/.*") ||
                path.matches("/projects/\\d+/videos/.*") ||
                path.matches("/image/projects/\\d+/.*") ||
                path.matches("/audio/projects/\\d+/.*") ||
                path.matches("/videos/projects/\\d+/.*") ||
                path.startsWith("/api/image-editor/elements") ||
                path.startsWith("/api/ai-voices/get-all-voices") ||
                path.startsWith("/api/ai-voices/voices-by-language") ||
                path.startsWith("/api/ai-voices/voices-by-gender") ||
                path.startsWith("/api/ai-voices/voices-by-language-and-gender") ||
                path.startsWith("/api/image-editor/templates") ||
                path.startsWith("/videos/")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Process JWT for authenticated endpoints
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                String email = jwtUtil.extractEmail(token);
                String role = jwtUtil.extractRole(token);

                // Restrict /developer endpoints to DEVELOPER role
                if (path.startsWith("/developer/") && !"DEVELOPER".equals(role)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.getWriter().write("Access denied: Developer role required");
                    return;
                }

                // Set authentication in context
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(email, null, null);
                authToken.setDetails(role);
                SecurityContextHolder.getContext().setAuthentication(authToken);
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("Invalid JWT token");
                return;
            }
        } else {
            response.setStatus(HttpStatus.SC_UNAUTHORIZED);
            response.getWriter().write("Authorization header missing or invalid");
            return;
        }

        filterChain.doFilter(request, response);
    }
}