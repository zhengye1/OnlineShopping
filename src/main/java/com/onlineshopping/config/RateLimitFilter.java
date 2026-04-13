package com.onlineshopping.config;

import com.onlineshopping.service.RedisService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RedisService redisService;
    public RateLimitFilter(RedisService redisService){
        this.redisService = redisService;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        boolean isLimited = redisService.isRateLimited("rate:"+ip, 30, 60);
        if (isLimited) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429, " +
                    "\"message\": \"Too Many Requests, please try again later\"}");

            return;
        }
        filterChain.doFilter(request ,response);
    }
}
