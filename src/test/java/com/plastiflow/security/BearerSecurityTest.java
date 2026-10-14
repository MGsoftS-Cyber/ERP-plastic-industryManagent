package com.plastiflow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "OIDC_ISSUER_URI=https://identity.example.test/realms/plastiflow")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BearerSecurityTest.DecoderConfiguration.class)
class BearerSecurityTest {
  @Autowired MockMvc api;

  @Test
  void bearerReaderCanReadButCannotCreate() throws Exception {
    api.perform(get("/api/v1/products").header("Authorization", "Bearer reader-token"))
        .andExpect(status().isOk());
    api.perform(
            post("/api/v1/products")
                .header("Authorization", "Bearer reader-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sku\":\"BEARER-READER\",\"name\":\"Resin\",\"kind\":\"RAW_MATERIAL\",\"unit\":\"KG\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void bearerManagerCanCreate() throws Exception {
    api.perform(
            post("/api/v1/products")
                .header("Authorization", "Bearer manager-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sku\":\"BEARER-MANAGER\",\"name\":\"Resin\",\"kind\":\"RAW_MATERIAL\",\"unit\":\"KG\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void unrelatedRoleCannotRead() throws Exception {
    api.perform(get("/api/v1/products").header("Authorization", "Bearer other-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidBearerTokenIsRejected() throws Exception {
    api.perform(get("/api/v1/products").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized());
  }

  @TestConfiguration
  static class DecoderConfiguration {
    // Tests authorization mapping only. Real signature/issuer/audience verification needs an OIDC
    // pilot test.
    @Bean
    JwtDecoder jwtDecoder() {
      return token -> {
        String role =
            switch (token) {
              case "reader-token" -> "CATALOG_READER";
              case "manager-token" -> "CATALOG_MANAGER";
              case "other-token" -> "OTHER_ROLE";
              default -> throw new BadJwtException("Invalid test token");
            };
        return Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject("test-user")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
      };
    }
  }
}
