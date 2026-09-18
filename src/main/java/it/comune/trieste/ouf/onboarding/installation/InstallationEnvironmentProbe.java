package it.comune.trieste.ouf.onboarding.installation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface InstallationEnvironmentProbe {
  record Finding(String check, String status, String detail) {
    public Finding {
      if (check == null || check.isBlank()) throw new IllegalArgumentException("check required");
      if (!"PASS".equals(status) && !"FAIL".equals(status)) throw new IllegalArgumentException("status PASS/FAIL");
      if (detail == null) detail = "";
    }
  }

  List<Finding> inspect(JsonNode installationConfiguration);
}
