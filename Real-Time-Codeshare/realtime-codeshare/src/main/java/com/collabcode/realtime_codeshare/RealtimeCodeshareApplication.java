package com.collabcode.realtime_codeshare;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RealtimeCodeshareApplication {

	public static void main(String[] args) {
		Dotenv dotenv= Dotenv.load();
		System.setProperty("MONGODB_URI", dotenv.get("MONGODB_URI"));
		SpringApplication.run(RealtimeCodeshareApplication.class, args);
	}

}
