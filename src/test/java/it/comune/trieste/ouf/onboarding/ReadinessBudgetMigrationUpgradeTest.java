package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL upgrade in a disposable database; no target/deployment authority. */
class ReadinessBudgetMigrationUpgradeTest {
  @Test void sourceRevisionV34RowsHistoryAndAppendOnlyTriggerSurviveExactlyOneV35Migration() throws Exception {
    String url=required("OUF_ONB_DB_URL"),user=required("OUF_ONB_DB_USER"),password=required("OUF_ONB_DB_PASSWORD");
    String database="ouf_budget_upgrade_"+UUID.randomUUID().toString().replace("-","");
    URI base=URI.create(url.substring("jdbc:".length()));
    String isolated="jdbc:"+new URI(base.getScheme(),base.getUserInfo(),base.getHost(),base.getPort(),"/"+database,base.getQuery(),base.getFragment());
    try(Connection control=DriverManager.getConnection(url,user,password);Statement admin=control.createStatement()) {
      admin.execute("CREATE DATABASE "+database);
      try {
        // Exact migration from the documented deployed be388e1 tree. Never replace V34 with the budget journal.
        byte[] migration;
        try(var stream=getClass().getResourceAsStream("/db/migration/V34__source_property_revision.sql")) {
          migration=Objects.requireNonNull(stream,"deployed V34 resource required").readAllBytes();
        }
        var git=MessageDigest.getInstance("SHA-1");
        git.update(("blob "+migration.length+"\0").getBytes(StandardCharsets.UTF_8));
        assertThat(HexFormat.of().formatHex(git.digest(migration))).isEqualTo("a6ee2ca93ae6b271e770e133e08da557bb379a09");
        Flyway.configure().dataSource(isolated,user,password).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("34").load().migrate();
        try(Connection data=DriverManager.getConnection(isolated,user,password);Statement sql=data.createStatement()) {
          String parent=UUID.randomUUID().toString(),child=UUID.randomUUID().toString(),challenge=UUID.randomUUID().toString();
          String parentHash="sha256:"+"a".repeat(64),hash="sha256:"+"b".repeat(64);
          sql.execute("INSERT INTO ouf_onboarding.source(source_id,name,source_kind,acquisition_mode,owner_ref) VALUES('upgrade-fixture','Upgrade fixture','EXTERNAL_API','PULL','fixture-owner')");
          sql.execute("INSERT INTO ouf_onboarding.onboarding_version(onboarding_version_id,source_id,version,state,configuration_hash,frozen_at) VALUES('"+parent+"','upgrade-fixture',1,'ACTIVE','"+parentHash+"',clock_timestamp())");
          sql.execute("INSERT INTO ouf_onboarding.onboarding_version(onboarding_version_id,source_id,version,state,parent_version_id,configuration_hash,frozen_at) VALUES('"+child+"','upgrade-fixture',2,'APPROVED','"+parent+"','"+hash+"',clock_timestamp())");
          sql.execute("INSERT INTO ouf_onboarding.approval_challenge(challenge_id,onboarding_version_id,source_id,configuration_hash,status,expires_at,confirmed_at) VALUES('"+challenge+"','"+child+"','upgrade-fixture','"+hash+"','CONFIRMED',clock_timestamp()-interval '1 hour',clock_timestamp()-interval '2 hours')");
          sql.execute("INSERT INTO ouf_onboarding.managed_onboarding_review(tenant_id,subject_id,command_key,request_hash,source_id,onboarding_version_id,challenge_id,configuration_hash,readiness_started_at,readiness_attempts,readiness_generation,readiness_phase,readiness_last_code) VALUES('fixture-tenant','fixture-human','review-key','"+hash+"','upgrade-fixture','"+child+"','"+challenge+"','"+hash+"',clock_timestamp()-interval '25 hours',3,4,'PAUSED','ONB_READINESS_DEPENDENCY_503')");
          sql.execute("INSERT INTO ouf_onboarding.source_property_revision(tenant_id,subject_id,command_key,request_hash,source_id,parent_version_id,parent_configuration_hash,onboarding_version_id,publication_effect) VALUES('fixture-tenant','fixture-human','revision-key','"+hash+"','upgrade-fixture','"+parent+"','"+parentHash+"','"+child+"','NEW_PUBLICATION_MAY_START_ACQUISITION')");
          String history=one(sql,"SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM ouf_onboarding.flyway_schema_history h WHERE version IS NOT NULL");
          String revision=one(sql,"SELECT to_jsonb(r)::text FROM ouf_onboarding.source_property_revision r");
          String review=one(sql,"SELECT (to_jsonb(r)-'readiness_session_id')::text FROM ouf_onboarding.managed_onboarding_review r");
          var flyway=Flyway.configure().dataSource(isolated,user,password).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("35").load();
          assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);flyway.validate();
          assertThat(one(sql,"SELECT jsonb_agg(to_jsonb(h) ORDER BY installed_rank)::text FROM ouf_onboarding.flyway_schema_history h WHERE version IS NOT NULL AND version<>'35'")).isEqualTo(history);
          assertThat(one(sql,"SELECT to_jsonb(r)::text FROM ouf_onboarding.source_property_revision r")).isEqualTo(revision);
          assertThat(one(sql,"SELECT (to_jsonb(r)-'readiness_session_id')::text FROM ouf_onboarding.managed_onboarding_review r")).isEqualTo(review);
          assertThat(one(sql,"SELECT script FROM ouf_onboarding.flyway_schema_history WHERE version='35' AND success")).isEqualTo("V35__managed_readiness_budget_sessions.sql");
          assertThat(one(sql,"SELECT count(*) FROM ouf_onboarding.managed_readiness_budget_session")).isEqualTo("0");
          assertThat(one(sql,"SELECT count(*) FROM ouf_onboarding.managed_onboarding_review WHERE readiness_session_id IS NULL")).isEqualTo("1");
          assertThatThrownBy(()->sql.execute("UPDATE ouf_onboarding.source_property_revision SET command_key='forbidden'"))
              .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
          assertThat(one(sql,"SELECT to_jsonb(r)::text FROM ouf_onboarding.source_property_revision r")).isEqualTo(revision);
        }
      } finally { admin.execute("DROP DATABASE "+database+" WITH (FORCE)"); }
    }
  }
  private static String one(Statement sql,String query)throws SQLException {
    try(ResultSet rows=sql.executeQuery(query)){assertThat(rows.next()).isTrue();String value=rows.getString(1);assertThat(rows.next()).isFalse();return value;}
  }
  private static String required(String name){return Objects.requireNonNull(System.getenv(name),name+" required");}
}

