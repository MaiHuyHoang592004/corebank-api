package com.corebank.corebank_api;

import org.springframework.boot.SpringApplication;

public class TestCorebankApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(CorebankApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
