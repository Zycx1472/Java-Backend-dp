package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    // 交换机名称
    public static final String SECKILL_EXCHANGE = "seckill.exchange";
    // 队列名称
    public static final String SECKILL_QUEUE = "seckill.order.queue";
    // Routing Key
    public static final String SECKILL_ROUTING_KEY = "seckill.order";

    @Bean
    public TopicExchange seckillExchange() {
        return new TopicExchange(SECKILL_EXCHANGE);
    }

    @Bean
    public Queue seckillQueue() {
        return QueueBuilder.durable(SECKILL_QUEUE)
                .lazy() // 关键：开启惰性队列模式
                .build();
    }

    @Bean
    public Binding seckillBinding() {
        return BindingBuilder.bind(seckillQueue()).to(seckillExchange()).with(SECKILL_ROUTING_KEY);
    }
}
