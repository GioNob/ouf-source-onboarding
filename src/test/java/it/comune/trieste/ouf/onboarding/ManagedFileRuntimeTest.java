package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import it.comune.trieste.ouf.onboarding.application.*;
import it.comune.trieste.ouf.onboarding.domain.CanonicalHash;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ManagedFileRuntimeTest {
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->required("OUF_ONB_DB_URL"));r.add("spring.datasource.username",()->required("OUF_ONB_DB_USER"));r.add("spring.datasource.password",()->required("OUF_ONB_DB_PASSWORD"));}
  @Autowired ManagedFileService files; @Autowired ManagedFileProfiler profiler; @Autowired CanonicalHash hashes; @Autowired JdbcClient db;
  @BeforeEach void clean(){db.sql("truncate table ouf_onboarding.file_profile,ouf_onboarding.managed_file_asset restart identity cascade").update();}
  @Test void csvProducesDeterministicSchemaAndCandidateKey(){byte[] csv="id;name;active;observed\n1;Alpha;true;2026-01-01\n2;Beta;false;2026-01-02\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);var asset=files.register(null,"object://staging/a.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:test","retention://30d");var profile=files.profile((UUID)asset.get("asset_id"),csv,"object://samples/a.json");assertThat(profile.get("format")).isEqualTo("CSV");assertThat((List<?>)profile.get("inferred_schema")).hasSize(4);assertThat((List<?>)profile.get("candidate_keys")).contains("id","name","observed");assertThat(db.sql("select count(*) from information_schema.columns where table_schema='ouf_onboarding' and table_name='managed_file_asset' and data_type='bytea'").query(Long.class).single()).isZero();}
  @Test void xlsxIsProfiledWithoutExecutingFormulas() throws Exception {byte[] bytes;try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=workbook.createSheet("objects");var header=sheet.createRow(0);header.createCell(0).setCellValue("id");header.createCell(1).setCellValue("value");var row=sheet.createRow(1);row.createCell(0).setCellValue("1");row.createCell(1).setCellValue(42);workbook.write(out);bytes=out.toByteArray();}var p=profiler.profile(bytes,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");assertThat(p.format()).isEqualTo("XLSX");assertThat(p.columns()).extracting(ManagedFileProfiler.ColumnProfile::name).containsExactly("id","value");}
  @Test void xlsxFormulaIsRejected() throws Exception {byte[] bytes;try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()){var sheet=workbook.createSheet("objects");sheet.createRow(0).createCell(0).setCellValue("value");sheet.createRow(1).createCell(0).setCellFormula("1+1");workbook.write(out);bytes=out.toByteArray();}byte[] payload=bytes;assertThatThrownBy(()->profiler.profile(payload,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).hasMessageContaining("formulas are not allowed");}
  @Test void hashMismatchFailsBeforePersistence(){byte[] registered="id,name\n1,A\n".getBytes();byte[] forged="id,name\n2,B\n".getBytes();var asset=files.register(null,"object://staging/a.csv",hashes.ofBytes(registered),"text/csv",forged.length,"human:test","retention://30d");assertThatThrownBy(()->files.profile((UUID)asset.get("asset_id"),forged,"object://sample")).hasMessageContaining("hash");assertThat(db.sql("select count(*) from ouf_onboarding.file_profile").query(Long.class).single()).isZero();}
  @Test void profileIsImmutable(){byte[] csv="id,name\n1,A\n".getBytes();var asset=files.register(null,"object://staging/a.csv",hashes.ofBytes(csv),"text/csv",csv.length,"human:test","retention://30d");var profile=files.profile((UUID)asset.get("asset_id"),csv,"object://sample");assertThatThrownBy(()->db.sql("delete from ouf_onboarding.file_profile where profile_id=:p").param("p",profile.get("profile_id")).update()).hasStackTraceContaining("file_profile is immutable");}
  private static String required(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" required");return v;}
}
