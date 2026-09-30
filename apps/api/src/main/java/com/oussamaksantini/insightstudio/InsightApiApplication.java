package com.oussamaksantini.insightstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// Accounts are handled by AccountService; no generated in-memory user.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class InsightApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsightApiApplication.class, args);
	}

}
