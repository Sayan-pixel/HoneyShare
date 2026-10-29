package com.honeyshare.config;

import com.honeyshare.config.AuthProperties;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Configures Argon2id password encoder with deployment-tuned parameters.
 *
 * <p>Parameters are sourced from {@link AuthProperties} so they can be
 * adjusted without code changes. Increasing memory/iterations degrades
 * brute-force attacks at the cost of login latency.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AuthProperties props) {
        AuthProperties.Argon2 a = props.getArgon2();
        return new Argon2PasswordEncoder(
                a.getSaltLength(),
                a.getHashLength(),
                a.getParallelism(),
                a.getMemoryKibibytes(),
                a.getIterations()
        );
    }
}

