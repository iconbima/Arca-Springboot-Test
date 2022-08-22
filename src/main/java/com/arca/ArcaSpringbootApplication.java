package com.arca;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.arca.rabbit.mq.*;

@SpringBootApplication
public class ArcaSpringbootApplication {

	public static void main(String[] args) {
		SpringApplication.run(ArcaSpringbootApplication.class, args);

		RabbitMQReceiver receiver = new RabbitMQReceiver();
		try {
			receiver.start();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (TimeoutException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

}
