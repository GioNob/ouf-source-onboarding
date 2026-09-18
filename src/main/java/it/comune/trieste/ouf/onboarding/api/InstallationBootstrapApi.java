package it.comune.trieste.ouf.onboarding.api;

import com.fasterxml.jackson.databind.JsonNode;
import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.installation.InstallationConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trusted-human/v1/installations")
public class InstallationBootstrapApi {
  private final InstallationConfigurationService configurations;

  public InstallationBootstrapApi(InstallationConfigurationService configurations) {
    this.configurations = configurations;
  }

  @GetMapping("/{installationId}/active")
  ResponseEntity<?> active(
      @PathVariable String installationId,
      HttpServletRequest request) {
    require(request, "installation.configuration.read", false);
    String id = safeInstallationId(installationId);
    var active = configurations.active(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND, "INSTALLATION_ACTIVE_NOT_FOUND"));
    return ResponseEntity.ok(active);
  }

  @GetMapping("/{installationId}/revisions/{revision}")
  ResponseEntity<?> revision(
      @PathVariable String installationId,
      @PathVariable long revision,
      HttpServletRequest request) {
    require(request, "installation.configuration.read", false);
    try {
      return ResponseEntity.ok(configurations.get(safeInstallationId(installationId), revision));
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @PostMapping("/{installationId}/revisions")
  @ResponseStatus(HttpStatus.CREATED)
  InstallationConfigurationService.Revision create(
      @PathVariable String installationId,
      @RequestBody JsonNode payload,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    var actor = actor(request, "installation.configuration.write", correlationId);
    String id = safeInstallationId(installationId);
    String bodyId = payload == null ? null : payload.path("installationId").asText(null);
    if (!id.equals(bodyId))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "INSTALLATION_ID_PATH_BODY_MISMATCH");
    try {
      return configurations.create(payload, actor);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  @PostMapping("/{installationId}/revisions/{revision}:validate-environment")
  InstallationConfigurationService.EnvironmentValidation validateEnvironment(
      @PathVariable String installationId,
      @PathVariable long revision,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    var actor = actor(request, "installation.configuration.write", correlationId);
    try {
      return configurations.validateEnvironment(
          safeInstallationId(installationId), revision, actor);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw lifecycleFailure(e);
    }
  }

  @PostMapping("/{installationId}/revisions/{revision}:activate")
  InstallationConfigurationService.Active activate(
      @PathVariable String installationId,
      @PathVariable long revision,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    var actor = actor(request, "installation.configuration.activate", correlationId);
    try {
      return configurations.activate(safeInstallationId(installationId), revision, actor);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw lifecycleFailure(e);
    }
  }

  @PostMapping("/{installationId}/revisions/{revision}:rollback")
  InstallationConfigurationService.Active rollback(
      @PathVariable String installationId,
      @PathVariable long revision,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    var actor = actor(request, "installation.configuration.activate", correlationId);
    String id = safeInstallationId(installationId);
    var current = configurations.active(id)
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "INSTALLATION_ROLLBACK_REQUIRES_ACTIVE"));
    if (revision >= current.revision())
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "INSTALLATION_ROLLBACK_TARGET_NOT_OLDER");
    try {
      return configurations.activate(id, revision, actor);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      throw lifecycleFailure(e);
    }
  }

  @PostMapping("/{installationId}/revisions/{revision}:revoke")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(
      @PathVariable String installationId,
      @PathVariable long revision,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    var actor = actor(request, "installation.configuration.activate", correlationId);
    try {
      configurations.revoke(safeInstallationId(installationId), revision, actor);
    } catch (NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  private InstallationConfigurationService.Actor actor(
      HttpServletRequest request,
      String capability,
      String correlationId) {
    if (correlationId == null || correlationId.isBlank())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "INSTALLATION_CORRELATION_REQUIRED");
    var context = require(request, capability, true);
    try {
      TrustedWriteProof.require(request);
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    }
    return new InstallationConfigurationService.Actor(
        context.principal().subjectId(), correlationId);
  }

  private ServletAuthorization.Context require(
      HttpServletRequest request,
      String capability,
      boolean humanOnly) {
    try {
      return ServletAuthorization.require(request, capability, true);
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    }
  }

  private String safeInstallationId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSTALLATION_ID_INVALID");
    return value;
  }

  private ResponseStatusException lifecycleFailure(IllegalStateException e) {
    HttpStatus status = switch (e.getMessage()) {
      case "INSTALLATION_REVISION_NOT_VALIDATED",
           "INSTALLATION_REVISION_REVOKED",
           "INSTALLATION_ENVIRONMENT_VALIDATION_REQUIRED" -> HttpStatus.CONFLICT;
      default -> HttpStatus.BAD_REQUEST;
    };
    return new ResponseStatusException(status, e.getMessage());
  }
}
