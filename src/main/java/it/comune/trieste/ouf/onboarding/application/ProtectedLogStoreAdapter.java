package it.comune.trieste.ouf.onboarding.application;

import java.time.OffsetDateTime;
import java.util.*;

public interface ProtectedLogStoreAdapter {
  record Query(OffsetDateTime from,OffsetDateTime to,String service,String severity,String correlationId,String eventType,int limit){}
  List<Map<String,Object>> search(Query query);
  Optional<Map<String,Object>> read(String logRef);
}
