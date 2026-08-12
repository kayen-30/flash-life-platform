FROM rabbitmq:3.13-management-alpine

# 安装延迟消息插件，版本需与 RabbitMQ 大版本匹配
RUN apk add --no-cache curl && \
    curl -fsSL \
        https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v3.13.0/rabbitmq_delayed_message_exchange-3.13.0.ez \
        -o /opt/rabbitmq/plugins/rabbitmq_delayed_message_exchange-3.13.0.ez && \
    rabbitmq-plugins enable rabbitmq_delayed_message_exchange && \
    apk del curl
