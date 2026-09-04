package com.hmdp;

import com.hmdp.api.ContentClient;
import com.hmdp.api.ShopClient;
import com.hmdp.api.TradeClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableFeignClients(clients = {ShopClient.class, TradeClient.class, ContentClient.class})
@EnableAsync
@SpringBootApplication
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
