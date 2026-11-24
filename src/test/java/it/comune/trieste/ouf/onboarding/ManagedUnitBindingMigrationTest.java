package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ManagedUnitBindingMigrationTest {
  @Test void f1_11_v46HistoryAndLegacyLayerSelectionSurviveV47()throws Exception{
    String url=Objects.requireNonNull(System.getenv("OUF_ONB_DB_URL")),user=System.getenv("OUF_ONB_DB_USER"),password=System.getenv("OUF_ONB_DB_PASSWORD");
    String database="ouf_unit_upgrade_"+UUID.randomUUID().toString().replace("-","");URI base=URI.create(url.substring(5));
    String isolated="jdbc:"+new URI(base.getScheme(),base.getUserInfo(),base.getHost(),base.getPort(),"/"+database,base.getQuery(),base.getFragment());
    try(Connection control=DriverManager.getConnection(url,user,password);Statement admin=control.createStatement()){
      admin.execute("create database "+database);
      try{
        Flyway.configure().dataSource(isolated,user,password).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("46").load().migrate();
        try(Connection data=DriverManager.getConnection(isolated,user,password);Statement sql=data.createStatement()){
          sql.execute("set search_path=ouf_onboarding,public");
          sql.execute("insert into source(source_id,name,source_kind,acquisition_mode,owner_ref) values('prior','Fixture','INTERNAL_MANAGED','MANAGED','fixture')");
          String asset="00000000-0000-4000-8000-000000000021",profile="00000000-0000-4000-8000-000000000022";
          sql.execute("insert into managed_file_asset(asset_id,source_id,staging_ref,content_hash,media_type,size_bytes,uploaded_by,retention_ref,status) values('"+asset+"','prior','object://staging/prior','sha256:"+"1".repeat(64)+"','application/geopackage+sqlite3',64,'fixture','retention://fixture','PROFILED')");
          sql.execute("insert into file_profile(profile_id,asset_id,version,format,metadata,inferred_schema,candidate_keys,sample_ref,redacted_sample) values('"+profile+"','"+asset+"',1,'GEOPACKAGE','{\"layers\":[{\"layer\":\"first\"},{\"layer\":\"second\"}]}','[]','[]','object://samples/prior','[]')");
          sql.execute("insert into onboarding_version(onboarding_version_id,source_id,version,state,configuration) values(gen_random_uuid(),'prior',1,'DRAFT',jsonb_build_object('extractionProfile',jsonb_build_object('selection',jsonb_build_object('assetId','"+asset+"'::text,'fileProfileId','"+profile+"'::text),'runtime',jsonb_build_object('layer','second'))))");
          String before=one(sql,"select to_jsonb(a)::text from managed_file_asset a");
          String history=one(sql,"select jsonb_agg(to_jsonb(h) order by installed_rank)::text from flyway_schema_history h where version is not null");
          var upgraded=Flyway.configure().dataSource(isolated,user,password).schemas("ouf_onboarding").defaultSchema("ouf_onboarding").target("47").load();
          assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
          upgraded.validate();
          assertThat(one(sql,"select jsonb_agg(to_jsonb(h) order by installed_rank)::text from flyway_schema_history h where version is not null and version<>'47'")).isEqualTo(history);
          assertThat(one(sql,"select to_jsonb(a)::text from managed_file_asset a")).isEqualTo(before);
          assertThat(one(sql,"select unit_ref from managed_file_effective_binding where source_id='prior'")).isEqualTo("layer:1");
          assertThat(one(sql,"select count(*) from managed_file_unit_binding")).isEqualTo("0");
        }
      }finally{admin.execute("drop database "+database+" with (force)");}
    }
  }
  private static String one(Statement sql,String query)throws SQLException{try(ResultSet r=sql.executeQuery(query)){assertThat(r.next()).isTrue();String value=r.getString(1);assertThat(r.next()).isFalse();return value;}}
}

