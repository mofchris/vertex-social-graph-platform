package com.vertex.graph;

import org.springframework.boot.SpringApplication;

public class TestGraphApplication {

	public static void main(String[] args) {
		SpringApplication.from(GraphApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
