package com.noopi.config;

import java.time.Clock;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RuntimeConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean RandomGenerator randomGenerator() { return new SecureRandom(); }
}
