package com.hmdp;

import com.hmdp.api.ContentClient;
import com.hmdp.api.ShopClient;
import com.hmdp.api.TradeClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients(clients = {ShopClient.class, TradeClient.class, ContentClient.class})
@SpringBootApplication
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
