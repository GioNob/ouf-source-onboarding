package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ProfileBudgetMigrationUpgradeTest {
  @Test void v36PreservesFailedFiveQuarantineAndAllPriorMigrationHistory()throws Exception{
    String url=Objects.requireNonNull(System.getenv("OUF_ONB_DB_URL")),user=System.getenv("OUF_ONB_DB_USER"),password=System.getenv("OUF_ONB_DB_PASSWORD");
    String database="ouf_profile_upgrade_"+UUID.randomUUID().toString().replace("-","");URI base=URI.create(url.substring(5));
    String isolated="jdbc:"+new URI(base.getScheme(),base.getUserInfo(),base.getHost(),base.getPort(),"/"+database,base.getQuery(),base.getFragment());
    try(Connection control=DriverManager.getConnection(url,user,password);Statement admin=control.createStatement()){
      admin.execute("create database "+database);
      try{
        Flyway.configure().dataSource(isolated,user,password).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("35").load().migrate();
        try(Connection data=DriverManager.getConnection(isolated,user,password);Statement sql=data.createStatement()){
          String asset=UUID.randomUUID().toString(),job=UUID.randomUUID().toString(),q=UUID.randomUUID().toString();
          sql.execute("insert into ouf_onboarding.managed_file_asset(asset_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status) values('"+asset+"','object://private/original','sha256:"+"a".repeat(64)+"','text/csv',13903,'fixture-human','retention://original','QUARANTINED')");
          sql.execute("insert into ouf_onboarding.file_profile_job(job_id,asset_id,idempotency_key,state,attempts,error_code,last_error,completed_at) values('"+job+"','"+asset+"','retained-key','FAILED',5,'ONB_FILE_PROFILE_FAILED','Gateway object read failed with status 403',clock_timestamp())");
          sql.execute("insert into ouf_onboarding.managed_file_quarantine(quarantine_id,asset_id,profile_job_id,reason_code,evidence_ref,safe_detail,correlation_id) values('"+q+"','"+asset+"','"+job+"','ONB_FILE_PROFILE_FAILED','quarantine://original','original evidence','fixture')");
          String history=one(sql,"select jsonb_agg(to_jsonb(h) order by installed_rank)::text from ouf_onboarding.flyway_schema_history h where version is not null");
          String beforeJob=one(sql,"select to_jsonb(j)::text from ouf_onboarding.file_profile_job j"),beforeAsset=one(sql,"select to_jsonb(a)::text from ouf_onboarding.managed_file_asset a"),beforeQuarantine=one(sql,"select to_jsonb(q)::text from ouf_onboarding.managed_file_quarantine q");
          var upgraded=Flyway.configure().dataSource(isolated,user,password).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("36").load();assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);upgraded.validate();assertThat(upgraded.migrate().migrationsExecuted).isZero();
          assertThat(one(sql,"select jsonb_agg(to_jsonb(h) order by installed_rank)::text from ouf_onboarding.flyway_schema_history h where version is not null and version<>'36'")).isEqualTo(history);
          assertThat(one(sql,"select (to_jsonb(j)-array['generation','lock_version','input_binding_hash','recovery_session_id','failure_class','budget_profile'])::text from ouf_onboarding.file_profile_job j")).isEqualTo(beforeJob);
          assertThat(one(sql,"select to_jsonb(a)::text from ouf_onboarding.managed_file_asset a")).isEqualTo(beforeAsset);
          assertThat(one(sql,"select to_jsonb(q)::text from ouf_onboarding.managed_file_quarantine q")).isEqualTo(beforeQuarantine);
          assertThat(one(sql,"select count(*) from ouf_onboarding.managed_profile_budget_session")).isEqualTo("0");
          assertThat(one(sql,"select count(*) from ouf_onboarding.managed_profile_event")).isEqualTo("0");
          assertThatThrownBy(()->sql.execute("update ouf_onboarding.file_profile_job set attempts=0")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be reset");
          assertThatThrownBy(()->sql.execute("update ouf_onboarding.managed_file_quarantine set safe_detail='rewritten'")).isInstanceOf(SQLException.class).hasMessageContaining("transition forbidden");
        }
      }finally{admin.execute("drop database "+database+" with (force)");}
    }
  }
  private static String one(Statement sql,String query)throws SQLException{try(ResultSet r=sql.executeQuery(query)){assertThat(r.next()).isTrue();String value=r.getString(1);assertThat(r.next()).isFalse();return value;}}
}

