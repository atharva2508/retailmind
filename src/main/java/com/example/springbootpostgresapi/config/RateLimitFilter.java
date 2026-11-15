package com.example.springbootpostgresapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    @Value("${app.rate-limit.max-requests:120}")
    private int maxRequests;
    private static final long WINDOW_MS = 10_000;
    private static final int MAX_BUCKETS = 10_000;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = request.getRemoteAddr();
        long now = System.currentTimeMillis();
        if (buckets.size() > MAX_BUCKETS) {
            buckets.values().removeIf(b -> now - b.windowStartMs > WINDOW_MS);
        }
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(now));

        synchronized (bucket) {
            if (now - bucket.windowStartMs > WINDOW_MS) {
                bucket.windowStartMs = now;
                bucket.count = 0;
            }
            if (bucket.count >= maxRequests) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Rate limit exceeded\"}");
                return;
            }
            bucket.count++;
        }

        filterChain.doFilter(request, response);
    }

    private static final class Bucket {
        private volatile long windowStartMs;
        private int count;

        private Bucket(long windowStartMs) {
            this.windowStartMs = windowStartMs;
        }
    }
}
