package it.comune.trieste.ouf.onboarding.api;

import it.comune.trieste.ouf.authorization.ServletAuthorization;
import it.comune.trieste.ouf.onboarding.installation.InstallationProjectionExportService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.OptionalLong;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/trusted-human/v1/installations")
public class InstallationProjectionExportApi {
  private final InstallationProjectionExportService exports;

  public InstallationProjectionExportApi(InstallationProjectionExportService exports) {
    this.exports = exports;
  }


  @GetMapping("/{installationId}/revisions/{revision}/projection")
  ResponseEntity<?> exportCandidate(
      @PathVariable String installationId,
      @PathVariable long revision,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    if (correlationId == null || correlationId.isBlank())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "INSTALLATION_EXPORT_CORRELATION_REQUIRED");
    String safeInstallationId = safeFilename(installationId);
    try {
      var authorization =
          ServletAuthorization.require(request, "installation.configuration.export", true);
      var actor = new InstallationProjectionExportService.Actor(
          authorization.principal().subjectId(),
          correlationId);
      var projection = exports.exportCandidate(safeInstallationId, revision, actor);
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .header(
              "Content-Disposition",
              "attachment; filename=\"installation-candidate-projection-"
                  + safeInstallationId
                  + "-r"
                  + projection.revision()
                  + ".json\"")
          .header("X-OUF-Installation-Revision", Long.toString(projection.revision()))
          .header("X-OUF-Installation-Checksum", projection.checksum())
          .header("X-OUF-Projection-Purpose", "CANDIDATE")
          .body(projection);
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (java.util.NoSuchElementException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    } catch (IllegalStateException e) {
      HttpStatus status = switch (e.getMessage()) {
        case "INSTALLATION_REVISION_NOT_VALIDATED" -> HttpStatus.CONFLICT;
        case "INSTALLATION_CANDIDATE_CHECKSUM_MISMATCH" -> HttpStatus.INTERNAL_SERVER_ERROR;
        default -> HttpStatus.BAD_REQUEST;
      };
      throw new ResponseStatusException(status, e.getMessage());
    }
  }

  @GetMapping("/{installationId}/projection")
  ResponseEntity<?> exportActive(
      @PathVariable String installationId,
      @RequestParam(required = false) Long revision,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
      HttpServletRequest request) {
    if (correlationId == null || correlationId.isBlank())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "INSTALLATION_EXPORT_CORRELATION_REQUIRED");
    String safeInstallationId = safeFilename(installationId);
    try {
      var authorization =
          ServletAuthorization.require(request, "installation.configuration.export", true);
      var actor = new InstallationProjectionExportService.Actor(
          authorization.principal().subjectId(),
          correlationId);
      var projection = exports.exportActive(
          safeInstallationId,
          revision == null ? OptionalLong.empty() : OptionalLong.of(revision),
          actor);
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .header(
              "Content-Disposition",
              "attachment; filename=\"installation-projection-"
                  + safeInstallationId
                  + "-r"
                  + projection.revision()
                  + ".json\"")
          .header("X-OUF-Installation-Revision", Long.toString(projection.revision()))
          .header("X-OUF-Installation-Checksum", projection.checksum())
          .body(projection);
    } catch (SecurityException e) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
    } catch (IllegalStateException e) {
      HttpStatus status = switch (e.getMessage()) {
        case "INSTALLATION_ACTIVE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
        case "INSTALLATION_EXPORT_REVISION_NOT_ACTIVE" -> HttpStatus.CONFLICT;
        case "INSTALLATION_ACTIVE_CHECKSUM_MISMATCH" -> HttpStatus.INTERNAL_SERVER_ERROR;
        default -> HttpStatus.BAD_REQUEST;
      };
      throw new ResponseStatusException(status, e.getMessage());
    }
  }

  private String safeFilename(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}"))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "INSTALLATION_ID_INVALID");
    return value;
  }
}
