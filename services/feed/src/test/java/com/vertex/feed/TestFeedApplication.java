package com.vertex.feed;

import org.springframework.boot.SpringApplication;

public class TestFeedApplication {

	public static void main(String[] args) {
		SpringApplication.from(FeedApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
