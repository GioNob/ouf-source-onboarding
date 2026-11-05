package it.comune.trieste.ouf.onboarding.installation;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Explicit isolated resolver pool; never replaces the Spring owner datasource.
 * No SQL grant, retry scheduler, failover or admission. Caller owns close(). */
public final class RecoveryPostgresDataSource {
  private RecoveryPostgresDataSource() {}
  public static HikariDataSource open(String host,int port,String database,String user,String password,boolean verifyTls){
    // A single literal address avoids unbounded DNS and multi-host failover. TLS still
    // verifies the configured IP against the server certificate; no fallback to plaintext.
    if(host==null||!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||port<1||port>65535||database==null||!database.matches("[A-Za-z0-9_]{1,63}")||user==null||user.isBlank()||password==null)throw new IllegalArgumentException("Explicit single IPv4 PostgreSQL binding required");
    for(String part:host.split("\\."))if(Integer.parseInt(part)>255)throw new IllegalArgumentException("Invalid PostgreSQL address");
    var c=new HikariConfig();c.setJdbcUrl("jdbc:postgresql://"+host+":"+port+"/"+database);
    c.setUsername(user);c.setPassword(password);c.setPoolName("ouf-recovery-outcome");
    c.setMaximumPoolSize(1);c.setMinimumIdle(0);c.setConnectionTimeout(1000);c.setValidationTimeout(500);c.setInitializationFailTimeout(-1);c.setReadOnly(true);
    c.addDataSourceProperty("connectTimeout","2");c.addDataSourceProperty("loginTimeout","3");c.addDataSourceProperty("socketTimeout","2");c.addDataSourceProperty("cancelSignalTimeout","1");
    c.addDataSourceProperty("sslmode",verifyTls?"verify-full":"disable");c.addDataSourceProperty("gssEncMode","disable");
    c.addDataSourceProperty("options","-c statement_timeout=2000 -c lock_timeout=1000 -c idle_in_transaction_session_timeout=5000");
    return new HikariDataSource(c);
  }
}
