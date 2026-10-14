package db.migration;

import java.util.*;
import org.flywaydb.core.api.migration.*;

/** Original V2 checks were unnamed. Discover the state check portably rather than rewriting V2. */
public class V8__production_abort extends BaseJavaMigration {
  @Override
  public Integer getChecksum() {
    return 2026101470;
  }

  @Override
  public void migrate(Context context) throws Exception {
    var db = context.getConnection();
    var names = new ArrayList<String>();
    try (var query =
        db.prepareStatement(
            "select c.constraint_name,cc.check_clause from information_schema.table_constraints c"
                + " join information_schema.check_constraints cc on"
                + " c.constraint_catalog=cc.constraint_catalog and"
                + " c.constraint_schema=cc.constraint_schema and"
                + " c.constraint_name=cc.constraint_name where lower(c.table_name)='mfg_order' and"
                + " c.constraint_schema=? and c.constraint_type='CHECK'")) {
      query.setString(1, db.getSchema());
      try (var rows = query.executeQuery()) {
        while (rows.next()) {
          String clause = rows.getString(2).toLowerCase(Locale.ROOT);
          // PostgreSQL information_schema also reports NOT NULL checks. Match the enum check.
          if (clause.contains("state")
              && clause.contains("planned")
              && clause.contains("scheduled")
              && clause.contains("running")
              && clause.contains("completed")) names.add(rows.getString(1));
        }
      }
    }
    if (names.size() != 1)
      throw new IllegalStateException("Expected exactly one production-order state check.");
    try (var sql = db.createStatement()) {
      sql.execute(
          "alter table mfg_order drop constraint \"" + names.get(0).replace("\"", "\"\"") + "\"");
      sql.execute(
          "alter table mfg_order add constraint ck_mfg_order_state check(state in"
              + " ('PLANNED','SCHEDULED','RUNNING','COMPLETED','ABORTED'))");
    }
  }
}
