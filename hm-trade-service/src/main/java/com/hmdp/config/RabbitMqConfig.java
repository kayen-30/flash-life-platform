package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 声明秒杀订单队列，并为消费异常配置有限重试和失败恢复。
 */
@Configuration
public class RabbitMqConfig {

    /**
     * 首次投递与 HTTP 线程隔离；队列饱和时快速拒绝，由 Redis 待发布任务兜底重投。
     */
    @Bean(name = RabbitMqConstants.ORDER_PUBLISH_EXECUTOR)
    public ThreadPoolTaskExecutor orderPublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("order-publish-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean
    public DirectExchange orderExchange() {
        return new DirectExchange(RabbitMqConstants.ORDER_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderQueue() {
        return QueueBuilder.durable(RabbitMqConstants.ORDER_QUEUE).build();
    }

    @Bean
    public Binding orderBinding(
            @Qualifier("orderQueue") Queue orderQueue,
            @Qualifier("orderExchange") DirectExchange orderExchange) {
        return BindingBuilder.bind(orderQueue).to(orderExchange).with(RabbitMqConstants.ORDER_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderFailedExchange() {
        return new DirectExchange(RabbitMqConstants.ORDER_FAILED_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderFailedQueue() {
        return QueueBuilder.durable(RabbitMqConstants.ORDER_FAILED_QUEUE).build();
    }

    @Bean
    public Binding orderFailedBinding(
            @Qualifier("orderFailedQueue") Queue orderFailedQueue,
            @Qualifier("orderFailedExchange") DirectExchange orderFailedExchange) {
        return BindingBuilder.bind(orderFailedQueue)
                .to(orderFailedExchange)
                .with(RabbitMqConstants.ORDER_FAILED_ROUTING_KEY);
    }

    /**
     * x-delayed-message 类型交换机，依赖 rabbitmq_delayed_message_exchange 插件。
     * 消息携带 x-delay 头（毫秒），到期后才路由到目标队列，每条消息独立计时。
     */
    @Bean
    public CustomExchange orderCancelExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(RabbitMqConstants.ORDER_CANCEL_EXCHANGE,
                "x-delayed-message", true, false, args);
    }

    @Bean
    public Queue orderCancelQueue() {
        return QueueBuilder.durable(RabbitMqConstants.ORDER_CANCEL_QUEUE).build();
    }

    @Bean
    public Binding orderCancelBinding(
            @Qualifier("orderCancelQueue") Queue orderCancelQueue,
            @Qualifier("orderCancelExchange") CustomExchange orderCancelExchange) {
        return BindingBuilder.bind(orderCancelQueue)
                .to(orderCancelExchange)
                .with(RabbitMqConstants.ORDER_CANCEL_ROUTING_KEY)
                .noargs();
    }

    @Bean
    public MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 主队列消费失败最多尝试三次，仍失败时交给恢复器补偿并留存失败消息。
     */
    @Bean(name = RabbitMqConstants.ORDER_LISTENER_FACTORY)
    public SimpleRabbitListenerContainerFactory orderRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageRecoverer messageRecoverer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // 秒杀订单默认四路并行消费，积压时最多扩到八路。
        factory.setConcurrentConsumers(4);
        factory.setMaxConcurrentConsumers(8);
        Advice retryAdvice = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(200L, 2.0, 1000L)
                .recoverer(messageRecoverer)
                .build();
        factory.setAdviceChain(retryAdvice);
        return factory;
    }
}
