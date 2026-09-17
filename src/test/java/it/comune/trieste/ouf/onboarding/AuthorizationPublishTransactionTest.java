package it.comune.trieste.ouf.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import it.comune.trieste.ouf.authorization.AuthorizationPolicy.CapabilityDescriptor;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.Grant;
import it.comune.trieste.ouf.authorization.AuthorizationPolicy.PolicyBundle;
import it.comune.trieste.ouf.authorization.PrincipalContext;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationAdminService;
import it.comune.trieste.ouf.onboarding.authorization.AuthorizationRuntimeSynchronizer;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AuthorizationPublishTransactionTest {
  @DynamicPropertySource
  static void db(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("OUF_ONB_DB_URL"));
    r.add("spring.datasource.username", () -> System.getenv("OUF_ONB_DB_USER"));
    r.add("spring.datasource.password", () -> System.getenv("OUF_ONB_DB_PASSWORD"));
  }

  @Autowired AuthorizationAdminService admin;
  @Autowired JdbcClient db;
  @Autowired PlatformTransactionManager transactionManager;
  @MockBean AuthorizationRuntimeSynchronizer runtimeSynchronizer;

  private final AuthorizationAdminService.Actor actor =
      new AuthorizationAdminService.Actor("admin", "tenant-a", "HUMAN", "fixture:1", "tx-test");

  @BeforeEach
  void clean() {
    db.sql("truncate ouf_authorization.admin_audit,ouf_authorization.policy_draft,ouf_authorization.capability_registration,ouf_authorization.authorization_decision_audit,ouf_authorization.active_policy_bundle,ouf_authorization.policy_bundle,ouf_authorization.bootstrap_latch cascade").update();
    db.sql("insert into ouf_authorization.bootstrap_latch(singleton_key,completed) values(true,false)").update();
    reset(runtimeSynchronizer);
  }

  private CapabilityDescriptor capability() {
    return new CapabilityDescriptor(
        "data.read", "READ", "data.read", Set.of(PrincipalContext.ActorType.HUMAN));
  }

  private PolicyBundle policy(long version) {
    var now = Instant.now();
    return new PolicyBundle(
        "tx-test",
        version,
        now,
        List.of(capability()),
        List.of(new Grant(
            "g1", "data.read", "tenant-a", "reader", null, null,
            now.minusSeconds(60), now.plusSeconds(3600))));
  }

  private AuthorizationAdminService.Draft prepareDraft() {
    admin.register("udp", capability(), actor);
    return admin.create(policy(1), actor);
  }

  @Test
  void committedPublishRefreshesRuntimeExactlyAfterCommit() {
    var draft = prepareDraft();
    var tx = new TransactionTemplate(transactionManager);

    tx.executeWithoutResult(status -> {
      admin.publish(draft.id(), draft.revision(), actor);
      verifyNoInteractions(runtimeSynchronizer);
      assertThat(db.sql("select count(*) from ouf_authorization.active_policy_bundle").query(Long.class).single())
          .isEqualTo(1);
    });

    verify(runtimeSynchronizer).refreshActive();
  }

  @Test
  void rolledBackPublishNeverRefreshesRuntimeAndLeavesNoActiveBundle() {
    var draft = prepareDraft();
    var tx = new TransactionTemplate(transactionManager);

    tx.executeWithoutResult(status -> {
      admin.publish(draft.id(), draft.revision(), actor);
      verifyNoInteractions(runtimeSynchronizer);
      status.setRollbackOnly();
    });

    verifyNoInteractions(runtimeSynchronizer);
    assertThat(db.sql("select count(*) from ouf_authorization.active_policy_bundle").query(Long.class).single())
        .isZero();
    assertThat(db.sql("select completed from ouf_authorization.bootstrap_latch where singleton_key=true").query(Boolean.class).single())
        .isFalse();
  }
}
