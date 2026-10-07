package io.spring;

import io.spring.logcore.LogcoreAppender;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RealWorldApplication {

  public static void main(String[] args) {
    LogcoreAppender.install();
    SpringApplication.run(RealWorldApplication.class, args);
  }
}

