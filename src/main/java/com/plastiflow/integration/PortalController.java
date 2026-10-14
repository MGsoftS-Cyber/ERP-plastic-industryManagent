package com.plastiflow.integration;

import static com.plastiflow.integration.Requests.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/portal")
@PreAuthorize("hasRole('PORTAL_USER')")
class PortalController {
  final PortalService portal;

  PortalController(PortalService portal) {
    this.portal = portal;
  }

  @GetMapping("/overview")
  Map<String, Object> overview() {
    return portal.overview();
  }

  @PostMapping("/requests")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, Object> request(@Valid @RequestBody PortalRequest r) {
    return portal.request(r);
  }

  @GetMapping("/requests/{id}")
  Map<String, Object> detail(@PathVariable UUID id) {
    return portal.ownRequest(id);
  }
}
