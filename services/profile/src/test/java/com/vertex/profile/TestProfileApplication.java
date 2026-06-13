package com.vertex.profile;

import org.springframework.boot.SpringApplication;

public class TestProfileApplication {

	public static void main(String[] args) {
		SpringApplication.from(ProfileApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
