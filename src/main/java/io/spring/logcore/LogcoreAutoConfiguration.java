package io.spring.logcore;

import javax.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LogcoreAutoConfiguration {

  @PostConstruct
  public void init() {
    LogcoreAppender.install();
  }
}
