package com.plastiflow.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@Profile("demo")
class DemoSecurityConfiguration {
  @Bean
  UserDetailsService demoUsers(
      @Value("${CATALOG_MANAGER_PASSWORD}") String manager,
      @Value("${CATALOG_READER_PASSWORD}") String reader,
      @Value("${PRODUCTION_PASSWORD}") String production,
      @Value("${QUALITY_PASSWORD}") String quality,
      @Value("${WORKFORCE_PASSWORD}") String workforce,
      @Value("${OPERATOR_PASSWORD}") String operator,
      @Value("${MAINTENANCE_PASSWORD}") String maintenance,
      @Value("${TECHNICIAN_PASSWORD}") String technician,
      @Value("${FACILITIES_PASSWORD}") String facilities,
      @Value("${LOGISTICS_PASSWORD}") String logistics,
      @Value("${SECURITY_PASSWORD}") String security,
      @Value("${GATE_PASSWORD}") String gate,
      @Value("${PAYROLL_PASSWORD}") String payroll,
      @Value("${PAYROLL_APPROVER_PASSWORD}") String payrollApprover,
      @Value("${ACCOUNTANT_PASSWORD}") String accountant,
      @Value("${FINANCE_PASSWORD}") String finance,
      @Value("${FINANCE_APPROVER_PASSWORD}") String financeApprover,
      @Value("${INTEGRATION_PASSWORD}") String integration,
      @Value("${REPORTS_PASSWORD}") String reports,
      @Value("${DEVICE_PASSWORD}") String device,
      @Value("${CUSTOMER_PASSWORD}") String customer,
      @Value("${CUSTOMER2_PASSWORD}") String customer2,
      @Value("${SUPPLIER_PASSWORD}") String supplier) {
    if (java.util.stream.Stream.of(
            manager,
            reader,
            production,
            quality,
            workforce,
            operator,
            maintenance,
            technician,
            facilities,
            logistics,
            security,
            gate,
            payroll,
            payrollApprover,
            accountant,
            finance,
            financeApprover,
            integration,
            reports,
            device,
            customer,
            customer2,
            supplier)
        .anyMatch(p -> p.length() < 16))
      throw new IllegalArgumentException("Demo passwords must contain at least 16 characters.");
    var encoder = new BCryptPasswordEncoder();
    return new InMemoryUserDetailsManager(
        User.withUsername("manager")
            .password("{bcrypt}" + encoder.encode(manager))
            .roles("CATALOG_MANAGER")
            .build(),
        User.withUsername("reader")
            .password("{bcrypt}" + encoder.encode(reader))
            .roles("CATALOG_READER")
            .build(),
        User.withUsername("production")
            .password("{bcrypt}" + encoder.encode(production))
            .roles("PRODUCTION_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("quality")
            .password("{bcrypt}" + encoder.encode(quality))
            .roles("QUALITY_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("workforce")
            .password("{bcrypt}" + encoder.encode(workforce))
            .roles("WORKFORCE_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("operator")
            .password("{bcrypt}" + encoder.encode(operator))
            .roles("OPERATOR", "CATALOG_READER")
            .build(),
        User.withUsername("maintenance")
            .password("{bcrypt}" + encoder.encode(maintenance))
            .roles("MAINTENANCE_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("technician")
            .password("{bcrypt}" + encoder.encode(technician))
            .roles("MAINTENANCE_TECHNICIAN", "CATALOG_READER")
            .build(),
        User.withUsername("facilities")
            .password("{bcrypt}" + encoder.encode(facilities))
            .roles("FACILITIES_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("logistics")
            .password("{bcrypt}" + encoder.encode(logistics))
            .roles("LOGISTICS_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("security")
            .password("{bcrypt}" + encoder.encode(security))
            .roles("SECURITY_MANAGER", "CATALOG_READER")
            .build(),
        User.withUsername("gate")
            .password("{bcrypt}" + encoder.encode(gate))
            .roles("GATE_OFFICER", "CATALOG_READER")
            .build(),
        User.withUsername("payroll")
            .password("{bcrypt}" + encoder.encode(payroll))
            .roles("PAYROLL_MANAGER")
            .build(),
        User.withUsername("payrollapprover")
            .password("{bcrypt}" + encoder.encode(payrollApprover))
            .roles("PAYROLL_APPROVER")
            .build(),
        User.withUsername("accountant")
            .password("{bcrypt}" + encoder.encode(accountant))
            .roles("ACCOUNTANT")
            .build(),
        User.withUsername("finance")
            .password("{bcrypt}" + encoder.encode(finance))
            .roles("FINANCE_MANAGER")
            .build(),
        User.withUsername("financeapprover")
            .password("{bcrypt}" + encoder.encode(financeApprover))
            .roles("FINANCE_APPROVER")
            .build(),
        User.withUsername("integration")
            .password("{bcrypt}" + encoder.encode(integration))
            .roles("INTEGRATION_MANAGER", "REPORT_VIEWER", "AI_ANALYST")
            .build(),
        User.withUsername("reports")
            .password("{bcrypt}" + encoder.encode(reports))
            .roles("REPORT_VIEWER", "AI_ANALYST")
            .build(),
        User.withUsername("device")
            .password("{bcrypt}" + encoder.encode(device))
            .roles("DEVICE_AGENT")
            .build(),
        User.withUsername("customer")
            .password("{bcrypt}" + encoder.encode(customer))
            .roles("PORTAL_USER")
            .build(),
        User.withUsername("customer2")
            .password("{bcrypt}" + encoder.encode(customer2))
            .roles("PORTAL_USER")
            .build(),
        User.withUsername("supplier")
            .password("{bcrypt}" + encoder.encode(supplier))
            .roles("PORTAL_USER")
            .build());
  }

  @Bean
  SecurityFilterChain demoSecurity(HttpSecurity http) throws Exception {
    // Local curl/API-client demonstration only. Production uses bearer JWT authentication.
    return SecurityConfiguration.base(http).httpBasic(Customizer.withDefaults()).build();
  }
}
