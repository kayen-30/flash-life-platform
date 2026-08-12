package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明商铺索引同步队列，并为 ES 临时不可用配置退避重试和重新入队。
 */
@Configuration
public class ShopSearchRabbitMqConfig {

    @Bean
    public DirectExchange shopSearchExchange() {
        return new DirectExchange(ShopSearchMqConstants.EXCHANGE, true, false);
    }

    @Bean
    public Queue shopSearchQueue() {
        return QueueBuilder.durable(ShopSearchMqConstants.QUEUE)
                .deadLetterExchange(ShopSearchMqConstants.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(ShopSearchMqConstants.DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding shopSearchBinding(
            @Qualifier("shopSearchQueue") Queue queue,
            @Qualifier("shopSearchExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ShopSearchMqConstants.ROUTING_KEY);
    }

    @Bean
    public DirectExchange shopSearchDeadLetterExchange() {
        return new DirectExchange(ShopSearchMqConstants.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue shopSearchDeadLetterQueue() {
        return QueueBuilder.durable(ShopSearchMqConstants.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding shopSearchDeadLetterBinding(
            @Qualifier("shopSearchDeadLetterQueue") Queue queue,
            @Qualifier("shopSearchDeadLetterExchange") DirectExchange exchange) {
        return BindingBuilder.bind(queue).to(exchange).with(ShopSearchMqConstants.DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public MessageConverter shopSearchRabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 单轮最多重试三次，仍失败则重新入队，等待 ES 恢复后继续消费。
     */
    @Bean(name = ShopSearchMqConstants.LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory shopSearchListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        Advice retryAdvice = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(500L, 2.0, 2000L)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
        factory.setAdviceChain(retryAdvice);
        return factory;
    }
}
