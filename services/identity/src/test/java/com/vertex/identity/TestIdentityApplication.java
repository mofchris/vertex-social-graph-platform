package com.vertex.identity;

import org.springframework.boot.SpringApplication;

public class TestIdentityApplication {

	public static void main(String[] args) {
		SpringApplication.from(IdentityApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
