package it.comune.trieste.ouf.onboarding.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.application.SourceRevisionService;
import it.comune.trieste.ouf.onboarding.application.ManagedCsvRevision;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SourceRevisionApiTest {
  // Pin the V34 wire representation used by CanonicalHash for commands already in the journal.
  private record OriginalPrepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
      String commandKey,String publicationEffect,List<SourceRevisionService.Change> changes){}
  @Test void existingPropertyCommandsKeepTheirFingerprint()throws Exception{
    UUID id=UUID.randomUUID();
    var change=new SourceRevisionService.Change("urn:p","raw",Map.of("version",1));
    var original=new OriginalPrepare("s",id,1,"hash","key",SourceRevisionService.EFFECT,List.of(change));
    var current=new SourceRevisionService.Prepare("s",id,1,"hash","key",SourceRevisionService.EFFECT,List.of(change));
    var hashes=new it.comune.trieste.ouf.onboarding.domain.CanonicalHash(new ObjectMapper());
    assertThat(hashes.of(current)).isEqualTo(hashes.of(original));
  }
  private record PreviousAssetPrepare(String sourceId,UUID parentVersionId,long expectedVersion,String configurationHash,
      String commandKey,String publicationEffect,List<SourceRevisionService.Change> changes,SourceRevisionService.AssetRef assetRef){}
  @Test void fingerprintPreservesExistingAssetCommandsAndIncludesNestedMappingIntent()throws Exception{
    var json=new ObjectMapper();
    var hashes=new it.comune.trieste.ouf.onboarding.domain.CanonicalHash(json);
    var ref=new SourceRevisionService.AssetRef(UUID.randomUUID(),UUID.randomUUID());
    var parent=UUID.randomUUID();
    var previous=new PreviousAssetPrepare("s",parent,1,"hash","key",SourceRevisionService.EFFECT,null,ref);
    var wire=json.valueToTree(previous);
    assertThat(hashes.of(json.treeToValue(wire,SourceRevisionService.Prepare.class))).isEqualTo(hashes.of(previous));
    var command=new SourceRevisionService.Prepare("s",parent,1,"hash","key",SourceRevisionService.EFFECT,null,ref,
        List.of(new ManagedCsvRevision.NewColumn("extra","MAP",new ManagedCsvRevision.Mapping("urn:extra","IDENTITY",
            Map.of("cardinality","SINGLE"),null,null,null))));
    var tree=json.valueToTree(command);
    assertThat(hashes.of(command)).isNotEqualTo(hashes.of(previous)).isEqualTo(hashes.of(json.treeToValue(tree,SourceRevisionService.Prepare.class)));
    ((com.fasterxml.jackson.databind.node.ObjectNode)tree.at("/newColumns/0/mapping/reconciliation")).put("cardinality","MULTI");
    assertThat(hashes.of(json.treeToValue(tree,SourceRevisionService.Prepare.class))).isNotEqualTo(hashes.of(command));
  }
  @Test void replacementRequiresOneClosedAssetSelectorAndTheSignedParentEtag()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(SourceRevisionService.class);
    when(receipts.verifySourceRevision(any(),anyString(),eq(SourceRevisionService.PREPARE),any()))
        .thenReturn(new PermissionDelegationVerifier.Delegated(null,"fixture"));
    var json=new ObjectMapper();var api=new SourceRevisionApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/source-revision-mcp/prepare");
    var ref=new SourceRevisionService.AssetRef(UUID.randomUUID(),UUID.randomUUID());
    var args=new LinkedHashMap<String,Object>(Map.of("sourceId","fixture","parentVersionId",UUID.randomUUID(),
        "commandKey","key","expectedVersion",7,"configurationHash","sha256:"+"a".repeat(64),
        "publicationEffect",SourceRevisionService.EFFECT,"assetRef",ref));
    var raw=json.writeValueAsBytes(Map.of("Arguments",args));
    assertThatThrownBy(()->api.prepare(raw,request)).hasMessage("Parent ETag required");
    request.addHeader("If-Match","W/\"ov:6\"");
    assertThatThrownBy(()->api.prepare(raw,request)).hasMessageContaining("differs");
    request.removeHeader("If-Match");request.addHeader("If-Match","W/\"ov:7\"");
    args.put("changes",List.of());
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(service);
    when(service.prepare(isNull(),argThat(c->ref.equals(c.assetRef())&&c.changes()==null),anyString()))
        .thenReturn(Map.of("state","IN_REVIEW","lockVersion",2));
    assertThat(api.prepare(raw,request).getHeaders().getETag()).isEqualTo("W/\"ov:2\"");
    args.remove("changes");
    args.put("newColumns",List.of(Map.of("column","extra","decision","EXCLUDE")));
    api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request);
    verify(service).prepare(isNull(),argThat(c->List.of(new ManagedCsvRevision.NewColumn("extra","EXCLUDE",null)).equals(c.newColumns())),anyString());
    args.put("newColumns",List.of(Map.of("column",123,"decision","EXCLUDE")));
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("newColumns",List.of(Map.of("column","extra","decision","MAP","mapping",Map.of("unknown",true))));
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.remove("assetRef");args.put("changes",List.of());
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void signedCommandsStillRequireClosedArgumentsAndAnExplicitParentCas()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(SourceRevisionService.class);
    when(receipts.verifySourceRevision(any(),anyString(),anyString(),any())).thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    var json=new ObjectMapper();var api=new SourceRevisionApi(receipts,service,json);
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/source-revision-mcp/prepare");
    var args=new LinkedHashMap<String,Object>(Map.of("sourceId","s","parentVersionId",UUID.randomUUID().toString(),"commandKey","key","configurationHash","sha256:"+"a".repeat(64),"publicationEffect",SourceRevisionService.EFFECT,"changes",List.of()));
    assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("expectedVersion","0");assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    args.put("expectedVersion",0);args.put("approvedBy","caller");assertThatThrownBy(()->api.prepare(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(service);
  }
  @Test void readHasExactlyOneSelectorAndCommandReconciliationDoesNotNeedTheLostVersionId()throws Exception{
    var receipts=mock(PermissionDelegationVerifier.class);var service=mock(SourceRevisionService.class);
    when(receipts.verifySourceRevision(any(),anyString(),anyString(),any())).thenReturn(new PermissionDelegationVerifier.Delegated(null,"key"));
    var json=new ObjectMapper();var api=new SourceRevisionApi(receipts,service,json);var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/source-revision-mcp/read");
    UUID parent=UUID.randomUUID();var args=new LinkedHashMap<String,Object>(Map.of("sourceId","s","parentVersionId",parent.toString(),"commandKey","key"));
    api.read(json.writeValueAsBytes(Map.of("Arguments",args)),request);verify(service).readCommand(null,"s",parent,"key");
    args.put("onboardingVersionId",UUID.randomUUID().toString());assertThatThrownBy(()->api.read(json.writeValueAsBytes(Map.of("Arguments",args)),request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoMoreInteractions(service);
  }
}
