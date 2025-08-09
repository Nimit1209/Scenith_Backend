package com.example.Scenith;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;

@SpringBootApplication
@EnableScheduling
public class ScenithApplication {
	private static final Logger logger = LoggerFactory.getLogger(ScenithApplication.class);


	public static void main(String[] args) {
		configureTrustStore();
		logEnvironmentInfo();
		if (System.getenv("RENDER") == null) {
			loadDotEnvFile();
		}
		logger.info("Starting VideoEditorApplication with B2 Bucket: {}", System.getenv("B2_BUCKET_NAME"));
		SpringApplication.run(ScenithApplication.class, args);
	}
	private static void configureTrustStore() {
		String[] trustStorePaths = {
				"src/main/resources/certs/planetscale-ca.jks",
				"/app/certs/planetscale-ca.jks"
		};
		boolean trustStoreConfigured = false;

		for (String trustStorePath : trustStorePaths) {
			File certFile = new File(trustStorePath);
			if (certFile.exists() && certFile.canRead()) {
				System.setProperty("javax.net.ssl.trustStore", trustStorePath);
				System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
				System.setProperty("javax.net.ssl.trustStoreType", "JKS");
				logger.info("SSL Trust Store configured successfully: {}", trustStorePath);
				trustStoreConfigured = true;
				break;
			} else {
				logger.warn("SSL Trust Store file not found or unreadable: {}", trustStorePath);
			}
		}

		if (!trustStoreConfigured) {
			logger.info("No valid SSL Trust Store found. Using system default trust store.");
			System.clearProperty("javax.net.ssl.trustStore");
			System.clearProperty("javax.net.ssl.trustStorePassword");
			System.clearProperty("javax.net.ssl.trustStoreType");
		}
	}

	private static void logEnvironmentInfo() {
		logger.info("=== Application Startup Information ===");
		logger.info("Startup time: {}", new java.util.Date());
		logger.info("DATABASE_URL: {}", maskUrl(System.getenv("DATABASE_URL")));
		logger.info("DATABASE_USERNAME: {}", System.getenv("DATABASE_USERNAME"));
		logger.info("DATABASE_PASSWORD: {}", System.getenv("DATABASE_PASSWORD") != null ? "***CONFIGURED***" : "NOT SET");
		logger.info("PORT: {}", System.getenv("PORT"));
		logger.info("SPRING_PROFILES_ACTIVE: {}", System.getenv("SPRING_PROFILES_ACTIVE"));
		logger.info("RENDER: {}", System.getenv("RENDER"));
		logger.info("B2_BUCKET_NAME: {}", System.getenv("B2_BUCKET_NAME"));
		logger.info("FRONTEND_URL: {}", System.getenv("FRONTEND_URL"));

		String[] certPaths = {
				"src/main/resources/certs/planetscale-ca.jks",
				"/app/certs/planetscale-ca.jks"
		};

		for (String path : certPaths) {
			File certFile = new File(path);
			logger.info("Certificate file [{}]: {}", path, certFile.exists() ? "EXISTS" : "NOT FOUND");
		}
		logger.info("========================================");
	}

	private static void loadDotEnvFile() {
		try {
			File envFile = new File("/app/.env");
			logger.info("Checking .env file at /app/.env: exists={}, readable={}",
					envFile.exists(), envFile.canRead());
			Dotenv dotenv = Dotenv.configure()
					.directory("/app")
					.ignoreIfMissing()
					.load();
			dotenv.entries().forEach(entry -> {
				logger.info("Loaded env: {}={}", entry.getKey(), entry.getValue());
				System.setProperty(entry.getKey(), entry.getValue());
			});
			logger.info("Successfully loaded .env file with {} entries", dotenv.entries().size());
		} catch (Exception e) {
			logger.error("Failed to load .env file: {}", e.getMessage(), e);
		}
	}

	private static String maskUrl(String url) {
		if (url == null) return "NOT SET";
		return url.replaceAll(":[^@]+@", ":***@");
	}
}