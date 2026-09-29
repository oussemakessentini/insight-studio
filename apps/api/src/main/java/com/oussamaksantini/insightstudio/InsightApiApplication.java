package com.oussamaksantini.insightstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InsightApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsightApiApplication.class, args);
	}

}
