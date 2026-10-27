package dev.relay.observability.operations;

import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/** Actuator security is separate from browser sessions and customer API-key authentication. */
@Configuration(proxyBeanMethods = false)
@Profile("!worker")
public class OperationsSecurityConfiguration {
    @Bean
    OperationsToken operationsToken(Environment environment) { return OperationsToken.from(environment); }

    @Bean
    @Order(0)
    SecurityFilterChain operationsChain(HttpSecurity http, OperationsToken token, PathMappedEndpoints endpoints)
            throws Exception {
        String health = endpoints.getPath(EndpointId.of("health"));
        var paths = PathPatternRequestMatcher.withDefaults();
        var probes = new OrRequestMatcher(paths.matcher(HttpMethod.GET, health + "/liveness"),
                paths.matcher(HttpMethod.GET, health + "/readiness"));
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> OperationsAuthenticationFilter.unauthorized(response))
                        .accessDeniedHandler((request, response, failure) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setStatus(403);
                        }))
                .authorizeHttpRequests(access -> access
                        .requestMatchers(probes).permitAll()
                        .requestMatchers(EndpointRequest.to("prometheus").withHttpMethod(HttpMethod.GET)).hasRole("OPERATIONS")
                        .anyRequest().denyAll())
                .addFilterBefore(new OperationsAuthenticationFilter(token, probes), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
