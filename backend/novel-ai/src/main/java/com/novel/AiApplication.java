package com.novel;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.mybatis.spring.annotation.MapperScan;
@SpringBootApplication
@EnableFeignClients
@MapperScan("com.novel.modules.ai.mapper")
public class AiApplication {
    public static void main(String[] args) { SpringApplication.run(AiApplication.class, args); }
}
