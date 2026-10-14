package com.plastiflow.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class MigrationUpgradeTest {
  @Test
  void upgradeFromV6PreservesRecordsAndRecoversOnlyKnownAdjustmentAuthors() {
    String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
    var source =
        new DriverManagerDataSource(
            System.getProperty(
                "spring.datasource.url",
                "jdbc:h2:mem:"
                    + schema
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"),
            System.getProperty("spring.datasource.username", "sa"),
            System.getProperty("spring.datasource.password", ""));
    var previous =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .target("6")
            .load();
    var current =
        Flyway.configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .cleanDisabled(false)
            .load();
    var db = new JdbcTemplate(source);
    try {
      previous.migrate();
      db.execute(
          "alter table "
              + schema
              + ".mfg_order add constraint extra_state_not_null check(state is not null)");
      UUID employee = UUID.randomUUID(),
          period = UUID.randomUUID(),
          known = UUID.randomUUID(),
          unknown = UUID.randomUUID();
      db.update(
          "insert into "
              + schema
              + ".mfg_employee values (?,'OLD-EMP','Existing worker','old-worker',true)",
          employee);
      db.update(
          "insert into "
              + schema
              + ".wf_period values (?,'OLD-PAY',DATE '2026-01-01',DATE"
              + " '2026-02-01','UTC','DZD',2,'DRAFT',null,null,null)",
          period);
      for (UUID adjustment : new UUID[] {known, unknown}) {
        db.update(
            "insert into "
                + schema
                + ".wf_adjustment values (?,?,?,'BONUS',10,'Historical adjustment','DRAFT',null)",
            adjustment,
            period,
            employee);
      }
      db.update(
          "insert into "
              + schema
              + ".wf_audit values (?,?,'ADJUSTMENT_REQUESTED','original-author','Old"
              + " audit',CURRENT_TIMESTAMP)",
          UUID.randomUUID(),
          known);
      UUID product = UUID.randomUUID(),
          recipe = UUID.randomUUID(),
          plan = UUID.randomUUID(),
          order = UUID.randomUUID();
      db.update(
          "insert into "
              + schema
              + ".catalog_product values (?,0,'OLD-SKU','Existing"
              + " product','FINISHED_GOOD','PIECE',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
          product);
      db.update(
          "insert into "
              + schema
              + ".mfg_recipe values (?,?,1,'INJECTION',1,'{}','box',CURRENT_TIMESTAMP)",
          recipe,
          product);
      db.update(
          "insert into " + schema + ".mfg_inspection_plan values (?,?,1,'[]')", plan, product);
      db.update(
          "insert into "
              + schema
              + ".mfg_order values (?,'OLD-ORDER','old-demand',?,?,10,0,0,'PLANNED')",
          order,
          recipe,
          plan);
      current.migrate();
      current.validate();
      assertThat(
              db.queryForObject(
                  "select name from " + schema + ".catalog_product where id=?",
                  String.class,
                  product))
          .isEqualTo("Existing product");
      assertThat(
              db.queryForObject(
                  "select created_by from " + schema + ".wf_adjustment where id=?",
                  String.class,
                  known))
          .isEqualTo("original-author");
      assertThat(
              db.queryForObject(
                  "select created_by from " + schema + ".wf_adjustment where id=?",
                  String.class,
                  unknown))
          .isNull();
      assertThat(
              db.queryForObject(
                  "select state from " + schema + ".mfg_order where id=?", String.class, order))
          .isEqualTo("PLANNED");
      db.update("update " + schema + ".mfg_order set state='ABORTED' where id=?", order);
      assertThat(
              db.queryForObject(
                  "select state from " + schema + ".mfg_order where id=?", String.class, order))
          .isEqualTo("ABORTED");
    } finally {
      current.clean();
    }
  }
}
