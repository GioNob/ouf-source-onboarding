package it.comune.trieste.ouf.onboarding.application;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class OnboardingMetrics implements MeterBinder {
  private final JdbcClient db;public OnboardingMetrics(JdbcClient db){this.db=db;}
  @Override public void bindTo(MeterRegistry registry){
    Gauge.builder("ouf_onboarding_active_sources",this,x->count("select count(*) from ouf_onboarding.published_configuration where active")).description("Sources with an ACTIVE bundle").register(registry);
    Gauge.builder("ouf_onboarding_pending_reviews",this,x->count("select count(*) from ouf_onboarding.onboarding_version where state='IN_REVIEW'")).description("Versions waiting for human review").register(registry);
    Gauge.builder("ouf_onboarding_job_backlog",this,x->count("select (select count(*) from ouf_onboarding.file_profile_job where state in ('READY','RETRY_WAIT'))+(select count(*) from ouf_onboarding.protected_log_export_job where state in ('READY','RETRY_WAIT'))")).description("Ready or retry-wait onboarding jobs").register(registry);
    Gauge.builder("ouf_onboarding_failed_jobs",this,x->count("select (select count(*) from ouf_onboarding.file_profile_job where state='FAILED')+(select count(*) from ouf_onboarding.protected_log_export_job where state='FAILED')")).description("Terminally failed onboarding jobs").register(registry);
  }
  private double count(String sql){try{return db.sql(sql).query(Long.class).single().doubleValue();}catch(Exception ignored){return Double.NaN;}}
}
