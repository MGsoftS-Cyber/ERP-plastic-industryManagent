package com.plastiflow.security;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
class SessionController {
  @GetMapping("/api/v1/session")
  Map<String, Object> session(Authentication auth) {
    return Map.of(
        "subject",
        auth.getName(),
        "roles",
        auth.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .map(a -> a.substring(5))
            .sorted()
            .toList());
  }
}
