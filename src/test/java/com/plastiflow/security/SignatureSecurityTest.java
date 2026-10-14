package com.plastiflow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignatureSecurityTest {
  static final RSAKey KEY = key();
  static final HttpServer IDP = start();

  static String issuer() {
    return "http://127.0.0.1:" + IDP.getAddress().getPort();
  }

  @Autowired MockMvc api;

  @DynamicPropertySource
  static void identity(DynamicPropertyRegistry registry) {
    registry.add("OIDC_ISSUER_URI", SignatureSecurityTest::issuer);
    registry.add("OIDC_AUDIENCE", () -> "plastiflow-api");
  }

  static RSAKey key() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
          .privateKey((RSAPrivateKey) pair.getPrivate())
          .keyID(UUID.randomUUID().toString())
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static HttpServer start() {
    try {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/.well-known/openid-configuration",
          exchange -> {
            String body =
                "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + issuer() + "/jwks\"}";
            send(exchange, body);
          });
      server.createContext(
          "/jwks", exchange -> send(exchange, new JWKSet(KEY.toPublicJWK()).toString()));
      server.start();
      return server;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static void send(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  String token(RSAKey key, String iss, String audience, long expiry, String role) throws Exception {
    var claims =
        new JWTClaimsSet.Builder()
            .issuer(iss)
            .subject("signed-device")
            .audience(audience)
            .issueTime(Date.from(Instant.now().minusSeconds(600)))
            .expirationTime(Date.from(Instant.now().plusSeconds(expiry)))
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Test
  void validSignatureIssuerAndAudienceAcceptReader() throws Exception {
    api.perform(
            get("/api/v1/products")
                .header(
                    "Authorization",
                    "Bearer " + token(KEY, issuer(), "plastiflow-api", 300, "CATALOG_READER")))
        .andExpect(status().isOk());
  }

  @Test
  void wrongIssuerAudienceAndExpiryAreRejected() throws Exception {
    for (String token :
        List.of(
            token(KEY, "https://wrong.example", "plastiflow-api", 300, "CATALOG_READER"),
            token(KEY, issuer(), "other-api", 300, "CATALOG_READER"),
            token(KEY, issuer(), "plastiflow-api", -120, "CATALOG_READER")))
      api.perform(get("/api/v1/products").header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongSignatureIsRejected() throws Exception {
    RSAKey forged = new RSAKey.Builder(key()).keyID(KEY.getKeyID()).build();
    api.perform(
            get("/api/v1/products")
                .header(
                    "Authorization",
                    "Bearer " + token(forged, issuer(), "plastiflow-api", 300, "CATALOG_READER")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void signedDeviceRoleCannotReadFinanceAndMapsToAgentEndpoint() throws Exception {
    String token = token(KEY, issuer(), "plastiflow-api", 300, "DEVICE_AGENT");
    api.perform(get("/api/v1/finance/overview").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    api.perform(
            post("/api/v1/integration/device-events")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    "{\"eventId\":\""
                        + UUID.randomUUID()
                        + "\",\"sequence\":1,\"occurredAt\":\""
                        + Instant.now()
                        + "\",\"data\":{\"person\":\"x\",\"kind\":\"IN\"}}"))
        .andExpect(status().isNotFound());
  }

  @AfterAll
  static void stop() {
    IDP.stop(0);
  }
}
