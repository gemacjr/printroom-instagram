package com.instagram.web;

import com.instagram.config.AppProperties;
import com.instagram.security.JwtAuthFilter;
import com.instagram.security.JwtService;
import com.instagram.security.SecurityConfig;
import com.instagram.support.TestFixtures;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Real security chain + JWT (bound from application.yml) for controller slice tests. */
@TestConfiguration
@Import({SecurityConfig.class, JwtAuthFilter.class})
public class WebSliceConfig {
    @Bean
    JwtService jwtService(AppProperties props) {
        return new JwtService(props, TestFixtures.CLOCK);
    }
}
