package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.comune.trieste.ouf.onboarding.api.ManagedFileMcpApi;
import it.comune.trieste.ouf.onboarding.api.PermissionDelegationVerifier;
import it.comune.trieste.ouf.onboarding.application.ManagedFileService;
import it.comune.trieste.ouf.onboarding.application.ManagedFileChatHandoff;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagedFileMcpApiTest {
  @Test void noSignedHumanReceiptCannotReachManagedAsset(){
    var files=mock(ManagedFileService.class);
    var receipts=new PermissionDelegationVerifier(new ObjectMapper(),"","https://iam.example","gateway","ouf-mcp-server");
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/profile");
    var api=new ManagedFileMcpApi(receipts,files,new ObjectMapper(),mock(ManagedFileChatHandoff.class));
    byte[] raw="{\"Arguments\":{\"assetId\":\"00000000-0000-4000-8000-000000000001\"}}".getBytes();
    assertThatThrownBy(()->api.profile(raw,request)).isInstanceOf(SecurityException.class);
    verifyNoInteractions(files);
  }
  @Test void uploadHandoffRequiresSignedHumanAndReturnsOnlyOwnedAssetIdentity(){
    var files=mock(ManagedFileService.class);
    var handoffs=mock(ManagedFileChatHandoff.class);
    var receipts=mock(PermissionDelegationVerifier.class);
    UUID id=UUID.randomUUID(),asset=UUID.randomUUID();
    byte[] raw=("{\"Arguments\":{\"handoffId\":\""+id+"\"}}").getBytes();
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/handoff");
    var principal=new PrincipalContext("human:alice","tenant-a",PrincipalContext.ActorType.HUMAN,
        "ouf-mcp-server","1","https://iam.example","gateway",Set.of("ouf.managed-source.file.upload"),
        new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.file.upload",raw))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal,"idem"));
    var observation=Map.<String,Object>of("assetId",asset,"status","STAGED","state","STAGED","observationVersion","2",
        "issuedAt","2030-03-01T08:00:00Z","waitUntil","2030-03-01T09:00:00Z");
    when(handoffs.result(id,"tenant-a","human:alice")).thenReturn(observation);
    var result=new ManagedFileMcpApi(receipts,files,new ObjectMapper(),handoffs).handoff(raw,request);
    assertThat(result).containsAllEntriesOf(observation)
        .containsOnlyKeys("assetId","status","state","observationVersion","issuedAt","waitUntil","workflowState","reasonCode","allowedActions");
    verify(handoffs).result(id,"tenant-a","human:alice");
    verifyNoInteractions(files);
  }
  @Test void jobStatusExposesOnlyBoundedFieldsAndChecksAssetOwner(){
    var files=mock(ManagedFileService.class);
    var receipts=mock(PermissionDelegationVerifier.class);
    UUID asset=UUID.randomUUID(),job=UUID.randomUUID(),profile=UUID.randomUUID();
    byte[] raw=("{\"Arguments\":{\"assetId\":\""+asset+"\",\"jobId\":\""+job+"\"}}").getBytes();
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
    var principal=new PrincipalContext("human:alice","tenant-a",PrincipalContext.ActorType.HUMAN,"ouf-mcp-server","1",
        "https://iam.example","gateway",Set.of("ouf.managed-source.preview"),
        new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.preview",raw))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal,"idem"));
    when(files.profileJob(asset,job)).thenReturn(Map.of("jobId",job,"status","SUCCEEDED","resultRef",profile,
        "staging_ref","object://private/secret","claimed_by","worker"));
    var columns=java.util.List.of(Map.of("name","code","distinctCount",2,"distinctValues",java.util.List.of("A"),
        "distinctValuesComplete",false,"distinctValuesCursor","continuation"));
    when(files.previewProfile(eq(asset),eq(profile),eq("tenant-a"),eq("human:alice"),anyMap())).thenAnswer(call->{
      var body=new java.util.LinkedHashMap<String,Object>(call.<Map<String,Object>>getArgument(4));
      body.put("columns",columns);
      body.put("nextOffset",1);
      return body;
    });
    var result=new ManagedFileMcpApi(receipts,files,new ObjectMapper(),mock(ManagedFileChatHandoff.class)).preview(raw,request);
    verify(files).requireOwner(asset,"human:alice");
    assertThat(result).containsEntry("jobId",job).containsEntry("status","SUCCEEDED").containsEntry("columns",columns)
        .containsEntry("resultRef",profile).containsEntry("nextOffset",1).containsKey("errorCode");
    assertThat(result.get("allowedActions")).isEqualTo(java.util.List.of(
        it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation.Action.READ_AGAIN,
        it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation.Action.READ_DISTINCT_VALUES));
    assertThat(result).doesNotContainKeys("staging_ref","claimed_by","redactedSample");
  }
  @Test void createDraftUsesDelegatedHumanAndIdempotentOwnerService(){
    var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);
    UUID asset=UUID.randomUUID(),profile=UUID.randomUUID();
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/create");
    byte[] raw=("{\"CorrelationID\":\"corr\",\"Arguments\":{\"assetId\":\""+asset+"\",\"profileId\":\""+profile+"\",\"sourceId\":\"cinema\",\"name\":\"Cinema\",\"owner\":\"Comune\",\"targetClassIri\":\"https://example.org/Cinema\",\"semanticRefs\":[\"core@1\"],\"sourceObjectKeyFields\":[],\"fields\":[{\"fieldName\":\"cinema\",\"extractionDecision\":\"INCLUDE\",\"dataAccessLabel\":\"OPEN\",\"targetPropertyIri\":\"https://example.org/name\"}]}}").getBytes();
    var principal=new PrincipalContext("human:alice","tenant-a",PrincipalContext.ActorType.HUMAN,"ouf-mcp-server","1",
        "https://iam.example","gateway",Set.of("ouf.managed-source.onboarding.create"),
        new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.onboarding.create",raw))
        .thenReturn(new PermissionDelegationVerifier.Delegated(principal,"idem"));
    when(files.onboardIdempotent(any(),any(),anyString(),anyString(),anyString(),anyString(),
        anyList(),anyList(),anyList(),isNull(),any(),anyString(),anyString(),anyString(),isNull(),isNull()))
        .thenReturn(Map.of("state","DRAFT","lockVersion",0));
    var api=new ManagedFileMcpApi(receipts,files,new ObjectMapper(),mock(ManagedFileChatHandoff.class));
    assertThat(api.create(raw,request)).containsEntry("allowedActions",java.util.List.of(
        it.comune.trieste.ouf.onboarding.application.ManagedMcpObservation.Action.PREPARE_REVIEW));
    var actor=org.mockito.ArgumentCaptor.forClass(it.comune.trieste.ouf.onboarding.application.OnboardingService.Actor.class);
    verify(files).onboardIdempotent(eq(asset),eq(profile),eq("cinema"),eq("Cinema"),eq("Comune"),eq("https://example.org/Cinema"),
        eq(java.util.List.of("core@1")),eq(java.util.List.of()),argThat(x->x.size()==1&&"cinema".equals(x.get(0).fieldName())),isNull(),actor.capture(),eq("corr"),eq("idem"),eq("tenant-a"),isNull(),isNull());
    assertThat(actor.getValue().subject()).isEqualTo("human:alice");
    assertThat(actor.getValue().type()).isEqualTo("HUMAN_USER");
    assertThat(actor.getValue().capabilities()).containsExactly("ouf.managed-source.onboarding.create");
  }

  @Test void ownedAssetListingUsesSignedTenantAndSubjectWithoutOpeningAnyAsset()throws Exception{
    var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);
    var json=new ObjectMapper();var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
    var p=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.HUMAN,"mcp","1","issuer","aud",
      Set.of("ouf.managed-source.preview"),new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    UUID cursor=UUID.randomUUID();byte[] raw=json.writeValueAsBytes(Map.of("Arguments",Map.of("view","ASSETS","limit",2,"afterAssetId",cursor.toString())));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.preview",raw)).thenReturn(new PermissionDelegationVerifier.Delegated(p,"read-only"));
    when(files.ownedAssets("tenant-a","alice",2,cursor)).thenReturn(Map.of("items",java.util.List.of(),"hasMore",false));
    var api=new ManagedFileMcpApi(receipts,files,json,mock(ManagedFileChatHandoff.class));
    assertThat(api.preview(raw,request)).containsEntry("hasMore",false);
    verify(files).ownedAssets("tenant-a","alice",2,cursor);verifyNoMoreInteractions(files);
  }
  @Test void ownerAssetQueryRejectsUnknownKeysCoercionAndUnboundedSelection()throws Exception{
    var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);
    var json=new ObjectMapper();var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
    var p=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.HUMAN,"mcp","1","issuer","aud",
      Set.of("ouf.managed-source.preview"),new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(isNull(),eq(request.getRequestURI()),eq("ouf.managed-source.preview"),any()))
      .thenReturn(new PermissionDelegationVerifier.Delegated(p,"read-only"));
    var api=new ManagedFileMcpApi(receipts,files,json,mock(ManagedFileChatHandoff.class));
    for(var args:java.util.List.of(Map.of("view","ASSETS","limit",0),Map.of("view","ASSETS","limit",51),Map.of("view","ASSETS","limit","2"),
        Map.of("view","ASSETS","limit",1.5),Map.of("view","ASSETS","tenant","other"),Map.of("view","ASSETS","afterAssetId","not-a-uuid"))){
      byte[] raw=json.writeValueAsBytes(Map.of("Arguments",args));
      assertThatThrownBy(()->api.preview(raw,request)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(files);
  }


  @Test void columnPageUsesExactTenantOwnerAndProfile()throws Exception{
    var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);
    var json=new ObjectMapper();UUID asset=UUID.randomUUID(),profile=UUID.randomUUID();
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/preview");
    byte[] raw=json.writeValueAsBytes(Map.of("Arguments",Map.of("view","COLUMNS","assetId",asset,"profileId",profile,"offset",128,"limit",128)));
    var p=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.HUMAN,"mcp","1","issuer","aud",Set.of("ouf.managed-source.preview"),new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(null,request.getRequestURI(),"ouf.managed-source.preview",raw)).thenReturn(new PermissionDelegationVerifier.Delegated(p,"read-only"));
    when(files.previewColumns(asset,profile,"tenant-a","alice",null,128,128,null)).thenReturn(Map.of("columnCount",1024,"hasMore",true));
    var result=new ManagedFileMcpApi(receipts,files,json,mock(ManagedFileChatHandoff.class)).preview(raw,request);
    verify(files).previewColumns(asset,profile,"tenant-a","alice",null,128,128,null);
    assertThat(result).containsEntry("columnCount",1024);
  }

  @Test @org.junit.jupiter.api.DisplayName("task51e-01: delegated create accepts 1024 fields and references")
  void delegatedCreateAccepts1024FieldsAndRejects1025BeforeOwnerWrite()throws Exception{
    var files=mock(ManagedFileService.class);var receipts=mock(PermissionDelegationVerifier.class);var json=new ObjectMapper();
    var request=new MockHttpServletRequest("POST","/api/internal/v1/onboarding/managed-file-mcp/create");
    var p=new PrincipalContext("alice","tenant-a",PrincipalContext.ActorType.HUMAN,"mcp","1","issuer","aud",Set.of("ouf.managed-source.onboarding.create"),new PrincipalContext.IdentityClaims(Set.of(),"1",Set.of(),null));
    when(receipts.verifyManagedFile(isNull(),eq(request.getRequestURI()),eq("ouf.managed-source.onboarding.create"),any(byte[].class))).thenReturn(new PermissionDelegationVerifier.Delegated(p,"wide-command"));
    var api=new ManagedFileMcpApi(receipts,files,json,mock(ManagedFileChatHandoff.class));
    var fields=new java.util.ArrayList<Map<String,Object>>();
    for(int i=0;i<1024;i++)fields.add(Map.of("fieldName","campo_"+i,"extractionDecision","INCLUDE","dataAccessLabel","OPEN","targetPropertyIri","https://example.org/campo_"+i));
    var args=new java.util.LinkedHashMap<String,Object>(Map.of("assetId",UUID.randomUUID(),"profileId",UUID.randomUUID(),"sourceId","sue-test","name","Synthetic SUE","owner","Synthetic","targetClassIri","https://example.org/Entity","semanticRefs",java.util.List.of("synthetic@1"),"sourceObjectKeyFields",java.util.List.of(),"fields",fields));
    var refs=java.util.stream.IntStream.range(0,1024).mapToObj(i->"urn:ref:"+i+"@1").toList();
    args.put("semanticRefs",refs);
    Map<String,Object> category=Map.of("schemeIri","urn:example:scheme","conceptIri","urn:example:concept");
    args.put("category",category);
    fields.set(0,Map.of("fieldName","campo_0","extractionDecision","INCLUDE","dataAccessLabel","OPEN",
        "targetPropertyIri","urn:wkt","transform","WKT_V1","transformParams",Map.of("crs","EPSG:4326")));
    var compositions=java.util.List.of(Map.<String,Object>of("kind","CONSTRUCTOR","constructorId","TEXT_JOIN_V1",
        "targetPropertyIri","urn:joined","dataAccessLabel","OPEN","inputs",java.util.List.of(
        Map.of("role","part","fieldName","campo_1","reader","IDENTITY"),Map.of("role","part","fieldName","campo_2","reader","IDENTITY")),
        "params",Map.of("separator"," ")));
    args.put("compositions",compositions);
    when(files.onboardIdempotent(any(),any(),anyString(),anyString(),anyString(),anyString(),
        anyList(),anyList(),anyList(),isNull(),any(),anyString(),anyString(),anyString(),eq(category),eq(compositions)))
        .thenReturn(Map.of("state","DRAFT","lockVersion",0));
    api.create(json.writeValueAsBytes(Map.of("Arguments",args,"CorrelationID","wide")),request);
    verify(files).onboardIdempotent(any(),any(),eq("sue-test"),any(),any(),any(),eq(refs),any(),argThat(x->x.size()==1024 && x.getFirst().transformParams().equals(Map.of("crs","EPSG:4326"))),isNull(),any(),eq("wide"),eq("wide-command"),eq("tenant-a"),eq(category),eq(compositions));
    fields.add(Map.of("fieldName","overflow","extractionDecision","EXCLUDE","dataAccessLabel","OPEN"));
    assertThatThrownBy(()->api.create(json.writeValueAsBytes(Map.of("Arguments",args,"CorrelationID","wide")),request)).hasMessageContaining("explicit field");
    verifyNoMoreInteractions(files);
  }
}
