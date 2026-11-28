package it.comune.trieste.ouf.onboarding.authorization;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ouf.authorization.analysis-mandate")
public record AnalysisMandateSettings(String installation, String issuer, String mcpWorkload, String thsClient,
                                      String gatewayCertSha256, URI rightsUri, String rightsSslBundle,
                                      @DefaultValue("2000") long clockSkewMs) {
  public AnalysisMandateSettings {
    if (!AnalysisMandate.identity(installation) || !AnalysisMandate.identity(issuer)
        || !AnalysisMandate.identity(mcpWorkload) || !AnalysisMandate.identity(thsClient)
        || !AnalysisMandate.hash(gatewayCertSha256) || clockSkewMs < 1 || clockSkewMs > 5000) {
      throw new IllegalStateException("Invalid analysis mandate installation configuration");
    }
  }
}
