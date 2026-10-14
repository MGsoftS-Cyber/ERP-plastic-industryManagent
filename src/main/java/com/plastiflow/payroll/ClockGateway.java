package com.plastiflow.payroll;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Device evidence bridge. Raw clocks never approve attendance or payroll. */
@Service
public class ClockGateway {
  private final AttendanceService attendance;

  public ClockGateway(AttendanceService attendance) {
    this.attendance = attendance;
  }

  public void capture(UUID event, UUID employee, String kind, Instant at, String source) {
    attendance.clock(
        new Requests.Clock(event, employee, Requests.ClockKind.valueOf(kind), at, source));
  }
}
