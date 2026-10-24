package com.flashgrid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class FlashGridApplication {

    public static void main(String[] args) {
        // Windows reports the legacy name "Asia/Calcutta", which newer PostgreSQL rejects.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        SpringApplication.run(FlashGridApplication.class, args);
    }
}