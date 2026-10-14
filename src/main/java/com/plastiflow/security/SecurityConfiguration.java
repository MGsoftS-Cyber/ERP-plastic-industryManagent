package com.plastiflow.security;

import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
  @Bean
  @Profile("!demo")
  SecurityFilterChain bearerSecurity(HttpSecurity http) throws Exception {
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          // Configure these roles in Keycloak's realm_access claim and the API audience mapper.
          Object raw = jwt.getClaim("realm_access");
          List<org.springframework.security.core.GrantedAuthority> authorities = new ArrayList<>();
          if (raw instanceof Map<?, ?> realm && realm.get("roles") instanceof Collection<?> roles) {
            for (Object role : roles)
              if (role instanceof String value
                  && Set.of(
                          "CATALOG_MANAGER",
                          "CATALOG_READER",
                          "PRODUCTION_MANAGER",
                          "OPERATOR",
                          "QUALITY_MANAGER",
                          "WORKFORCE_MANAGER",
                          "MAINTENANCE_MANAGER",
                          "MAINTENANCE_TECHNICIAN",
                          "FACILITIES_MANAGER",
                          "LOGISTICS_MANAGER",
                          "SECURITY_MANAGER",
                          "GATE_OFFICER",
                          "PAYROLL_MANAGER",
                          "PAYROLL_APPROVER",
                          "ACCOUNTANT",
                          "FINANCE_MANAGER",
                          "FINANCE_APPROVER",
                          "INTEGRATION_MANAGER",
                          "DEVICE_AGENT",
                          "REPORT_VIEWER",
                          "AI_ANALYST",
                          "PORTAL_USER")
                      .contains(value))
                authorities.add(new SimpleGrantedAuthority("ROLE_" + value));
          }
          return authorities;
        });
    return base(http)
        .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }

  static HttpSecurity base(HttpSecurity http) throws Exception {
    // This API is stateless, has no cookies and must not enable credentialed cross-origin browser
    // access.
    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(HttpMethod.GET, "/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/v1/session")
                    .authenticated()
                    .requestMatchers("/api/v1/products", "/api/v1/products/**")
                    .authenticated()
                    .requestMatchers("/api/v1/integration/**", "/api/v1/portal/**")
                    .authenticated()
                    .requestMatchers("/api/v1/finance/**")
                    .authenticated()
                    .requestMatchers("/api/v1/payroll/**")
                    .authenticated()
                    .requestMatchers("/api/v1/operations/**")
                    .authenticated()
                    .requestMatchers("/api/v1/manufacturing/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll());
  }
}
