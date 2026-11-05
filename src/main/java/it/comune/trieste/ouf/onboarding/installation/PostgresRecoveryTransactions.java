package it.comune.trieste.ouf.onboarding.installation;

import java.math.BigInteger;
import java.sql.*;
import java.time.*;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Capture from the exact Spring-bound owner connection, never from a second connection.
 * Explicit PG17 candidate; no bean, migration, admission issuer or automatic restart rebind.
 * DataSource connection acquisition must already have a finite configured pool/login timeout.
 */
public final class PostgresRecoveryTransactions {
  public record Database(String systemIdentifier,long databaseOid,Instant postmasterStartedAt) {
    public Database {
      if(!unsigned(systemIdentifier)||databaseOid<1||databaseOid>4294967295L||postmasterStartedAt==null)
        throw new IllegalArgumentException("Valid admitted PostgreSQL identity required");
    }
  }
  public record Transaction(Database database,String xid8) {
    public Transaction {
      if(database==null||!unsigned(xid8)||new BigInteger(xid8).compareTo(BigInteger.valueOf(3))<0)
        throw new IllegalArgumentException("Valid PostgreSQL transaction identity required");
    }
  }
  private static final String IDENTITY="s.system_identifier::text, d.oid::bigint, pg_catalog.pg_postmaster_start_time(), pg_catalog.pg_is_in_recovery(), pg_catalog.current_setting('server_version_num')::int";
  private static final String FROM=" from pg_catalog.pg_control_system() s cross join pg_catalog.pg_database d where d.datname=pg_catalog.current_database()";
  private final DataSource dataSource;private final Database admitted;
  public PostgresRecoveryTransactions(DataSource dataSource,Database admitted){this.dataSource=Objects.requireNonNull(dataSource);this.admitted=Objects.requireNonNull(admitted);}
  public Database admitted(){return admitted;}
  /** Observation for a separately governed admission/provisioning action, not an admission. */
  public static Database observe(DataSource ds){
    if(TransactionSynchronizationManager.isActualTransactionActive())throw denied("Database admission observation requires no owner transaction");
    try(var c=ds.getConnection()){return identity(c);}catch(SQLException ex){throw denied("PostgreSQL identity unavailable",ex);}
  }
  Transaction capture(){
    if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.hasResource(dataSource))throw denied("Exact owner datasource transaction required");
    Connection c=DataSourceUtils.getConnection(dataSource);
    if(!DataSourceUtils.isConnectionTransactional(c,dataSource))throw denied("Exact owner datasource transaction required");
    try{
      if(c.getAutoCommit()||c.isReadOnly())throw denied("Writable owner transaction required");
      int timeout=c.getNetworkTimeout();c.setNetworkTimeout(Runnable::run,2000);
      try(var s=c.createStatement()){
        s.setQueryTimeout(2);
        try(var r=s.executeQuery("select "+IDENTITY+", pg_catalog.pg_current_xact_id()::text, pg_catalog.current_setting('synchronous_commit')"+FROM)){
          if(!r.next())throw denied("PostgreSQL identity unavailable");Database database=database(r);
          if(!admitted.equals(database)||!"on".equals(r.getString(7)))throw denied("PostgreSQL admission or synchronous commit differs");
          return new Transaction(database,r.getString(6));
        }
      }finally{c.setNetworkTimeout(Runnable::run,timeout);}
    }catch(SQLException ex){throw denied("Owner transaction binding unavailable",ex);}
    // Spring owns this connection. Do not close or release it here.
  }
  static Database identity(Connection c)throws SQLException{
    int timeout=c.getNetworkTimeout();c.setNetworkTimeout(Runnable::run,2000);
    try(var s=c.createStatement()){
      s.setQueryTimeout(2);try(var r=s.executeQuery("select "+IDENTITY+FROM)){
        if(!r.next())throw denied("PostgreSQL identity unavailable");return database(r);
      }
    }finally{c.setNetworkTimeout(Runnable::run,timeout);}
  }
  static String status(Connection c,Transaction transaction)throws SQLException{
    if(!transaction.database().equals(identity(c)))throw denied("PostgreSQL database or server incarnation differs");
    int timeout=c.getNetworkTimeout();c.setNetworkTimeout(Runnable::run,2000);
    try(var s=c.prepareStatement("select pg_catalog.pg_xact_status(cast(? as xid8))")){
      s.setQueryTimeout(2);s.setString(1,transaction.xid8());try(var r=s.executeQuery()){
        if(!r.next())throw denied("PostgreSQL transaction status unavailable");return r.getString(1);
      }
    }finally{c.setNetworkTimeout(Runnable::run,timeout);}
  }
  private static Database database(ResultSet r)throws SQLException{
    int version=r.getInt(5);if(r.getBoolean(4)||version<170000||version>=180000)throw denied("Qualified PostgreSQL 17 primary required");
    return new Database(r.getString(1),r.getLong(2),r.getObject(3,OffsetDateTime.class).toInstant());
  }
  private static boolean unsigned(String value){return value!=null&&value.matches("[1-9][0-9]{0,19}")&&new BigInteger(value).bitLength()<=64;}
  private static IllegalStateException denied(String message){return new IllegalStateException(message);}
  private static IllegalStateException denied(String message,Throwable cause){return new IllegalStateException(message,cause);}
}
