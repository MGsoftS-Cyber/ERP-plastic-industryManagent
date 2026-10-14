package com.plastiflow.integration;

import com.fasterxml.jackson.databind.JsonNode;

interface AiProvider {
  boolean enabled();

  String model();

  JsonNode generate(JsonNode input) throws Exception;
}
