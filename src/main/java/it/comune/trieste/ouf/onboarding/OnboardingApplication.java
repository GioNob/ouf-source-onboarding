package it.comune.trieste.ouf.onboarding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OnboardingApplication {
  public static void main(String[] args) { SpringApplication.run(OnboardingApplication.class,args); }
}
