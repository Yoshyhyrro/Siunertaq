// =============================================================================
// build.sbt additions for ClickHouse + Mecrisp-Stellaris pipeline
//
// Apply these changes to the existing build.sbt.
// =============================================================================

// ── New version constants (add to existing block) ────────────────────────────

val ClickHouseVersion  = "0.6.5"    // com.clickhouse:clickhouse-jdbc (Apache 2.0)
// Note: we use the pure Java HTTP client (no extra dep) for the sync actor.
// clickhouse-jdbc is optional: add it if you need Connection/ResultSet semantics
// for PostgreSQL-mirror queries from Scala (e.g., running dead_forth_words query).

// ── Updated postgres-bridge definition ───────────────────────────────────────
//
// Replace the existing lazy val postgresBridge block with:

lazy val postgresBridge = (project in file("modules/postgres-bridge"))
  .dependsOn(core, batchBridge, petersenMzv)
  .settings(
    name := "Siunertaq-postgres",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= commonDependencies ++ Seq(
      // Cats Effect (IO, Ref)
      "org.typelevel"  %% "cats-effect"       % CatsEffectVersion,
      // PostgreSQL JDBC
      "org.postgresql"  % "postgresql"         % "42.7.3",
      // Doobie + Skunk (pure-functional DB, future use)
      "org.tpolecat"   %% "doobie-core"        % "1.0.0-RC2",
      "org.tpolecat"   %% "skunk-core"         % "0.6.5",
      // ASM: JVM bytecode → opcode extraction
      "org.ow2.asm"     % "asm"                % "9.7",
      // Pekko classic (ForthRegistrarActor, ClickHouseSyncActor)
      "org.apache.pekko" %% "pekko-actor"      % PekkoVersion,
      // ClickHouse JDBC client (optional; HTTP client used by default)
      // Uncomment to enable Connection/ResultSet access from Scala:
      // "com.clickhouse" % "clickhouse-jdbc" % ClickHouseVersion classifier "all",
      //
      // ClickHouse Java client (lightweight, no Spark dep):
      // "com.clickhouse" % "clickhouse-client"  % ClickHouseVersion,
      // "com.clickhouse" % "clickhouse-http-client" % ClickHouseVersion,
    ),
    // Test: run integration tests only with explicit env var
    // RUN_CLICKHOUSE_SMOKE=1 sbt "postgresBridge/test"
    testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oDF")
  )

// ── ClickHouse environment variables (for local dev) ─────────────────────────
//
// Add to your .env or shell profile:
//
//   export CLICKHOUSE_HOST=localhost
//   export CLICKHOUSE_PORT=8123
//   export CLICKHOUSE_DB=siunertaq
//   export CLICKHOUSE_USER=default
//   export CLICKHOUSE_PASS=
//
// Start ClickHouse locally (Docker):
//
//   docker run -d \
//     --name siunertaq-ch \
//     -p 8123:8123 -p 9000:9000 \
//     -e CLICKHOUSE_DB=siunertaq \
//     -v $(pwd)/clickhouse-data:/var/lib/clickhouse \
//     clickhouse/clickhouse-server:24.3-alpine
//
// Apply schema:
//   clickhouse-client --multiline \
//     < modules/postgres-bridge/extension/clickhouse_schema.sql

// ── PostgreSQL logical replication (for MaterializedPostgreSQL option) ────────
//
// In postgresql.conf:
//   wal_level = logical
//   max_replication_slots = 10
//   max_wal_senders = 10
//
// Grant permission:
//   ALTER USER siunertaq REPLICATION;
//
// In ClickHouse (uncomment in clickhouse_schema.sql):
//   CREATE DATABASE siunertaq_pg_replica
//   ENGINE = MaterializedPostgreSQL('localhost:5432', 'siunertaq', 'siunertaq', 'pass')
//   SETTINGS materialized_postgresql_tables_list = 'mzv_triple_log,compiled_words';
