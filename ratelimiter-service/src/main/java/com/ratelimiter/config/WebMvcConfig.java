package com.ratelimiter.config;

import com.ratelimiter.profiling.RequestProfilingInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnBean(RequestProfilingInterceptor.class)
public class WebMvcConfig implements WebMvcConfigurer {

    private final RequestProfilingInterceptor requestProfilingInterceptor;

    public WebMvcConfig(RequestProfilingInterceptor requestProfilingInterceptor) {
        this.requestProfilingInterceptor = requestProfilingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestProfilingInterceptor).addPathPatterns("/api/**");
    }
}
