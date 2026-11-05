package it.comune.trieste.ouf.onboarding.application;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Loopback-only PostgreSQL v3 fixture. Never logs authentication/data frames.
 * Real sockets and JDBC errors; no fabricated commit result or Spring notification.
 */
final class PostgresCommitFaultProxy implements AutoCloseable {
  enum Fault { NONE, LOSE_REPLY, DROP_COMMIT, DELAY_COMMIT }
  final ServerSocket listener;final URI target;final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
  final Set<Pair> pairs=ConcurrentHashMap.newKeySet();final AtomicReference<Fault> armed=new AtomicReference<>(Fault.NONE);
  final AtomicBoolean closed=new AtomicBoolean(),commitForwarded=new AtomicBoolean(),serverCommitObserved=new AtomicBoolean();
  final CountDownLatch intercepted=new CountDownLatch(1),terminalReply=new CountDownLatch(1);
  volatile Pair delayed;volatile byte[] delayedCommit;
  PostgresCommitFaultProxy()throws Exception{
    target=URI.create(System.getenv("OUF_ONB_DB_URL").substring(5));
    if(!InetAddress.getByName(target.getHost()).isLoopbackAddress())throw new IllegalArgumentException("Loopback fixture only");
    listener=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
    threads.submit(()->{while(!closed.get())try{
      Socket client=listener.accept();Socket server=new Socket();server.connect(new InetSocketAddress(target.getHost(),target.getPort()),2000);
      client.setSoTimeout(10000);server.setSoTimeout(10000);var pair=new Pair(client,server);pairs.add(pair);
      threads.submit(()->frontend(pair));threads.submit(()->backend(pair));
    }catch(IOException ex){if(!closed.get())throw new UncheckedIOException(ex);}});
  }
  DriverManagerDataSource datasource(){return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+listener.getLocalPort()+target.getRawPath()+"?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&connectTimeout=2&socketTimeout=5",System.getenv("OUF_ONB_DB_USER"),System.getenv("OUF_ONB_DB_PASSWORD"));}
  void arm(Fault fault){if(fault==Fault.NONE||!armed.compareAndSet(Fault.NONE,fault))throw new IllegalStateException("Fault already armed");}
  void releaseDelayedCommit()throws Exception{
    if(!intercepted.await(5,TimeUnit.SECONDS)||delayed==null)throw new AssertionError("No delayed COMMIT");
    delayed.fault=Fault.LOSE_REPLY;send(delayed,delayedCommit);commitForwarded.set(true);
    if(!terminalReply.await(5,TimeUnit.SECONDS))throw new AssertionError("No server COMMIT result");
  }
  private void frontend(Pair pair){
    try{
      var in=new DataInputStream(pair.client.getInputStream());int size=in.readInt();
      if(size<8||size>16384)throw new IOException("Invalid startup frame");
      byte[] startup=in.readNBytes(size-4);if(startup.length!=size-4)throw new EOFException();
      synchronized(pair){var out=new DataOutputStream(pair.server.getOutputStream());out.writeInt(size);out.write(startup);out.flush();}
      while(!pair.closed.get()){
        int type=in.read();if(type<0)return;byte[] frame=frame(type,in);
        boolean commit=type=='Q'&&new String(frame,5,frame.length-5,StandardCharsets.UTF_8).equals("COMMIT\0");
        Fault fault=armed.get();
        if(commit&&fault!=Fault.NONE&&armed.compareAndSet(fault,Fault.NONE)){
          pair.fault=fault;
          if(fault==Fault.DELAY_COMMIT){pair.retained=true;delayed=pair;delayedCommit=frame;pair.client.close();intercepted.countDown();return;}
          if(fault==Fault.DROP_COMMIT){pair.close();intercepted.countDown();return;}
          send(pair,frame);commitForwarded.set(true);intercepted.countDown();
        }else send(pair,frame);
      }
    }catch(IOException ignored){/* Expected connection loss or orderly disconnect. */}
    finally{if(!pair.retained)pair.close();}
  }
  private void backend(Pair pair){
    try{
      var in=new DataInputStream(pair.server.getInputStream());var out=pair.client.getOutputStream();
      while(!pair.closed.get()){
        int type=in.read();if(type<0)return;byte[] frame=frame(type,in);
        if(pair.fault==Fault.LOSE_REPLY){
          if(type=='C'&&new String(frame,5,frame.length-5,StandardCharsets.UTF_8).equals("COMMIT\0")){
            serverCommitObserved.set(true);terminalReply.countDown();pair.close();return;
          }
        }else{out.write(frame);out.flush();}
      }
    }catch(IOException ignored){/* Expected transport failure in the fixture. */}
    finally{if(!pair.retained||pair.fault!=Fault.DELAY_COMMIT)pair.close();}
  }
  private static byte[] frame(int type,DataInputStream in)throws IOException{
    int size=in.readInt();if(size<4||size>1024*1024)throw new IOException("Invalid protocol frame");
    byte[] body=in.readNBytes(size-4);if(body.length!=size-4)throw new EOFException();
    var bytes=new ByteArrayOutputStream(size+1);var out=new DataOutputStream(bytes);out.writeByte(type);out.writeInt(size);out.write(body);return bytes.toByteArray();
  }
  private static void send(Pair pair,byte[] frame)throws IOException{synchronized(pair){var out=pair.server.getOutputStream();out.write(frame);out.flush();}}
  private static final class Pair {
    final Socket client,server;final AtomicBoolean closed=new AtomicBoolean();volatile Fault fault=Fault.NONE;volatile boolean retained;
    Pair(Socket client,Socket server){this.client=client;this.server=server;}
    void close(){if(closed.compareAndSet(false,true)){try{client.close();}catch(IOException ignored){}try{server.close();}catch(IOException ignored){}}}
  }
  public void close(){
    if(closed.compareAndSet(false,true)){
      try{listener.close();}catch(IOException ignored){}pairs.forEach(Pair::close);threads.shutdownNow();
      try{if(!threads.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Proxy fixture threads did not stop");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
    }
  }
}
