package com.plastiflow.operations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reuses existing operational validation; never controls physical hardware. */
@Service
public class DeviceGateway {
  private final AccessService access;
  private final YardService yard;

  public DeviceGateway(AccessService access, YardService yard) {
    this.access = access;
    this.yard = yard;
  }

  public void camera(UUID id, String state) {
    access.cameraStatus(id, new Requests.CameraStatus(Requests.CameraState.valueOf(state)));
  }

  public void weight(UUID visit, UUID event, String kind, BigDecimal kg, Instant at) {
    yard.weight(visit, new Requests.Weight(event, Requests.WeightKind.valueOf(kind), kg, at));
  }
}
