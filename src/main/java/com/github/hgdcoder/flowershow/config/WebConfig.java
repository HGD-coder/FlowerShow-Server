package com.github.hgdcoder.flowershow.config;

import java.nio.file.Paths;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${flower-show.upload.root:uploads}")
    private String uploadRoot;

    @Value("${flower-show.cors.allowed-origin-patterns:*}")
    private List<String> allowedOriginPatterns;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()
                ? List.of("*")
                : allowedOriginPatterns.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(securityHeadersInterceptor());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadRoot).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }

    private static HandlerInterceptor securityHeadersInterceptor() {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(
                    jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,
                    Object handler
            ) {
                // Prevent user-uploaded files from being executed/interpreted as
                // active content when served from the application origin.
                response.setHeader("X-Content-Type-Options", "nosniff");
                if (request.getRequestURI().startsWith("/uploads/")) {
                    response.setHeader("Content-Security-Policy", "sandbox; default-src 'none'");
                }
                return true;
            }
        };
    }
}
