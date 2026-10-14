package shop;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** A small app for the tests: its controllers, its SQL and its logs are the app's code. */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ShopApplication {}
