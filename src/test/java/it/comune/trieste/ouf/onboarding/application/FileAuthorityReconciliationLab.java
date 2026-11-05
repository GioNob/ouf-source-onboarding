package it.comune.trieste.ouf.onboarding.application;

import it.comune.trieste.ouf.onboarding.installation.FilePlatformRecoveryAuthority;
import static it.comune.trieste.ouf.onboarding.installation.PlatformAuthorityReconciliation.*;
import java.sql.*;
import java.time.Instant;

/** Synthetic trusted decision/outcome oracle for a known fixture transaction only.
 * Real PostgreSQL NOWAIT control-row exclusion; NOT a production outcome resolver.
 * The expected phase/outcome is supplied by the test that owns the transaction.
 */
final class FileAuthorityReconciliationLab {
  static Gate gate(Outcome outcome,String expectedPhase){return request->{
    Connection c=null;
    try{
      c=UploadCrashProcess.datasource().getConnection();c.setAutoCommit(false);
      try(var statement=c.createStatement()){
        statement.execute("set local statement_timeout='2s'");
        try(var row=statement.executeQuery("select phase from ouf_onboarding.managed_file_storage_control where singleton=true for update nowait")){
          if(!row.next()||!expectedPhase.equals(row.getString(1)))throw new IllegalStateException("Fixture owner outcome differs");
        }
        String identity;
        try(var row=statement.executeQuery("select system_identifier::text from pg_control_system()")){row.next();identity="postgres/"+row.getString(1);}
        var proof=new Evidence(FilePlatformRecoveryAuthority.reconciliationFingerprint(request),identity,"lab/owner/"+expectedPhase,outcome,Instant.now(),Instant.now().plusSeconds(30));
        Connection held=c;
        return new Lease(){
          public Evidence current(){try{if(held.isClosed())throw new IllegalStateException("Fixture exclusion lost");return proof;}catch(SQLException ex){throw new IllegalStateException(ex);}}
          public void close(){try{try{held.rollback();}finally{held.close();}}catch(SQLException ex){throw new IllegalStateException(ex);}}
        };
      }
    }catch(Exception ex){
      if(c!=null)try{c.close();}catch(SQLException ignored){}
      if(ex instanceof SQLException sql&&"55P03".equals(sql.getSQLState()))return null;
      throw new IllegalStateException("Fixture owner reconciliation unavailable",ex);
    }
  };}
}
