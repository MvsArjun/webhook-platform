package dev.relay.identity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        var delegate = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(12)));
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence password) {
                if (!withinByteLimit(password)) throw new IllegalArgumentException("Invalid password length");
                return delegate.encode(password);
            }

            @Override
            public boolean matches(CharSequence password, String encodedPassword) {
                // BCrypt matching historically truncates long inputs. Reject them instead.
                return withinByteLimit(password) && delegate.matches(password, encodedPassword);
            }

            @Override
            public boolean upgradeEncoding(String encodedPassword) {
                return delegate.upgradeEncoding(encodedPassword);
            }
        };
    }

    static boolean withinByteLimit(CharSequence password) {
        return password != null && password.length() <= 72
                && password.toString().getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}
