package io.github.eytoptas.teamchest;

import org.springframework.boot.SpringApplication;

public class TestTeamChestApplication {

	public static void main(String[] args) {
		SpringApplication.from(TeamChestApplication::main).with(TestcontainersConfiguration.class).run(args);
	}
}
