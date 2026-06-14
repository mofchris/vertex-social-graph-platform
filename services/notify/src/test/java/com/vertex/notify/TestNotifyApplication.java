package com.vertex.notify;

import org.springframework.boot.SpringApplication;

public class TestNotifyApplication {

	public static void main(String[] args) {
		SpringApplication.from(NotifyApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
