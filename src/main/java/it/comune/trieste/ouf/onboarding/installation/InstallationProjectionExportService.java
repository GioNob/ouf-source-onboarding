package it.comune.trieste.ouf.onboarding.installation;

import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstallationProjectionExportService {
  private final InstallationConfigurationService configurations;
  private final InstallationRuntimeProjectionService projections;
  private final JdbcClient db;

  public InstallationProjectionExportService(
      InstallationConfigurationService configurations,
      InstallationRuntimeProjectionService projections,
      JdbcClient db) {
    this.configurations = configurations;
    this.projections = projections;
    this.db = db;
  }

  public record Actor(String subject, String correlationId) {
    public Actor {
      if (subject == null || subject.isBlank())
        throw new IllegalArgumentException("INSTALLATION_EXPORT_ACTOR_REQUIRED");
      if (correlationId == null || correlationId.isBlank())
        throw new IllegalArgumentException("INSTALLATION_EXPORT_CORRELATION_REQUIRED");
    }
  }

  @Transactional
  public InstallationRuntimeProjectionService.Projection exportActive(
      String installationId,
      OptionalLong expectedRevision,
      Actor actor) {
    var active = configurations.active(installationId)
        .orElseThrow(() -> new IllegalStateException("INSTALLATION_ACTIVE_NOT_FOUND"));
    if (expectedRevision.isPresent() && expectedRevision.getAsLong() != active.revision())
      throw new IllegalStateException("INSTALLATION_EXPORT_REVISION_NOT_ACTIVE");

    var projection = projections.project(installationId, active.revision());
    if (!projection.checksum().equals(active.checksum()))
      throw new IllegalStateException("INSTALLATION_ACTIVE_CHECKSUM_MISMATCH");

    db.sql("""
        insert into ouf_installation.installation_projection_export_event
        (export_id,installation_id,revision,configuration_checksum,actor_subject,correlation_id)
        values(:event,:id,:rev,:checksum,:actor,:correlation)
        """)
        .param("event", UUID.randomUUID())
        .param("id", installationId)
        .param("rev", active.revision())
        .param("checksum", active.checksum())
        .param("actor", actor.subject())
        .param("correlation", actor.correlationId())
        .update();

    return projection;
  }
}
