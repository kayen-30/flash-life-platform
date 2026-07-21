# 黑马点评微服务版

项目已经从单体拆分为 Spring Cloud 多模块应用，外部接口路径保持不变，统一通过 `hm-gateway` 访问。

## 模块

| 模块 | 端口 | 职责 |
| --- | ---: | --- |
| `hm-gateway` | 10010 | 路由、Redis Token 鉴权、可信用户身份传递 |
| `hm-user-service` | 8081 | 登录、用户资料、签到 |
| `hm-shop-service` | 8082 | 店铺、分类、GEO 查询与缓存 |
| `hm-trade-service` | 8083 | 优惠券、RabbitMQ 秒杀订单、Sentinel 限流 |
| `hm-content-service` | 8084 | 笔记、评论、关注、点赞、Feed、图片上传 |
| `hm-ai-service` | 8085 | AI 客服、店铺推荐、RAG 与业务工具调用 |
| `hm-common` | - | 统一响应、用户上下文、内部接口鉴权 |
| `hm-api` | - | 跨服务 DTO 和 Feign 契约，不包含业务实体 |

## Docker 部署

需要 Docker Desktop。Docker Compose 会构建 6 个 Java 服务，并统一运行以下组件：

| 组件 | 本机端口 | 用途 |
| --- | ---: | --- |
| MySQL 8 | 13306 | `dian-ping` 业务数据库，避开本机 `MySQL80` 的 3306 |
| Redis 8 | 6379 | 登录、缓存、Feed 和秒杀资格预扣 |
| Redis Stack | 6380 | AI RAG 向量检索 |
| RabbitMQ | 5672 / 15672 | 秒杀订单队列 / 管理台 |
| Nacos 3 | 8848 | 服务注册与配置中心 |
| Sentinel Dashboard | 8858 | 流量监控与规则查看 |
| Nginx | 8080 | 前端静态资源和 `/api` 网关代理 |
| Gateway | 10010 | 容器内服务的统一后端入口 |
| Trade Sentinel API | 8719 | 交易服务 Sentinel 命令端点，仅用于本地调试 |

首次切换到容器前，应先停止占用 `6379` 和 `8080` 的本机进程。Docker MySQL 使用 `13306`，
不要求停止占用 `3306` 的本机 `MySQL80`。启动全部组件：

```powershell
docker compose up -d --build
```

MySQL 首次创建数据卷时会执行 `deploy/mysql/hmdp.sql`；后续重启不会重复初始化。前端默认挂载
`../nginx-1.18.0-hmdp/html/hmdp`，可通过 `HMDP_FRONTEND_DIR` 覆盖。常用入口：

- 前端：`http://localhost:8080`
- 网关：`http://localhost:10010`
- Nacos API：`http://localhost:8848`
- Nacos 3 控制台：`http://localhost:18048`
- Sentinel Dashboard：`http://localhost:8858`，默认账号密码为 `sentinel/sentinel`
- RabbitMQ 管理台：`http://localhost:15672`，默认账号密码为 `hmdp/hmdp-rabbit`

Nacos 3 首次创建数据卷时需要初始化控制台管理员。本地开发可在控制台设置为 `nacos/nacos`，
对外部署时必须换成强密码。

数据保存在 Docker named volumes 中。`docker compose down` 只停止并删除容器；不要使用
`docker compose down -v`，除非确认可以删除 MySQL、Redis、Redis Stack、RabbitMQ 和 Nacos 数据。

Java 服务在 Compose 网络中通过 `mysql`、`redis`、`redis-stack`、`rabbitmq`、`nacos` 和
`sentinel-dashboard` 容器名通信，业务服务端口不会发布到宿主机。查看服务状态和日志：

```powershell
docker compose ps
docker compose logs -f gateway trade-service
```

需要在 IDE 中调试 Java 服务时，可以只启动基础设施，再分别运行本地模块：

```powershell
docker compose up -d mysql redis redis-stack rabbitmq nacos sentinel-dashboard frontend
mvn -f hm-user-service/pom.xml spring-boot:run
mvn -f hm-shop-service/pom.xml spring-boot:run
mvn -f hm-content-service/pom.xml spring-boot:run
mvn -f hm-trade-service/pom.xml spring-boot:run
mvn -f hm-ai-service/pom.xml spring-boot:run
mvn -f hm-gateway/pom.xml spring-boot:run
```

统一后端入口是 `http://localhost:10010`。Nginx 将浏览器的 `/api/**` 请求直接转发到 Compose
网络中的 `gateway:10010`。

## 配置

本地密钥继续放在被 Git 忽略的 `.env` 中。常用环境变量：

- `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`
- `REDIS_HOST`、`REDIS_PORT`
- `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`
- `NACOS_ADDR`、`NACOS_ENABLED`
- `HMDP_INTERNAL_TOKEN`：Feign 内部接口共享凭证
- `HMDP_ADMIN_USER_IDS`：允许维护店铺和优惠券的用户 id，多个值使用逗号分隔
- `HMDP_AI_RAG_REDIS_HOST`、`HMDP_AI_RAG_REDIS_PORT`：Redis Stack 地址，默认端口为 `6380`
- `SENTINEL_DASHBOARD`：Sentinel 控制台地址，默认 `127.0.0.1:8858`
- `HMDP_UPLOAD_DIR`：内容图片目录，多实例部署应替换为 MinIO/S3
- `DEEPSEEK_API_KEY`、`SPRING_AI_CHAT_CLIENT_ENABLED`
- `HMDP_AI_CHAT_RATE_LIMIT`、`HMDP_AI_RECOMMEND_RATE_LIMIT`：单用户每分钟模型调用上限

`X-Internal-Token` 不是用户登录 Token，而是 Feign 调用 `/internal/**` 接口时自动携带的服务间共享凭证。
被调用服务会校验它，不匹配时返回 `403`。所有服务必须配置相同的 `HMDP_INTERNAL_TOKEN`；默认值只适合本地开发，
生产环境还应使用强随机值并通过内网或安全组禁止外部直接访问业务服务端口。

Sentinel 已接入交易服务，`seckillVoucher` 资源超过阈值时会执行快速失败。规则模板位于
`deploy/nacos/hm-trade-service-flow-rules.json`，模板文件不会自动进入 Nacos，可在项目根目录执行：

```powershell
$login = Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:18048/v3/auth/user/login' `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{ username = 'nacos'; password = 'nacos' }
$content = Get-Content -Raw deploy/nacos/hm-trade-service-flow-rules.json
Invoke-RestMethod -Method Post `
    -Uri 'http://127.0.0.1:18048/v3/console/cs/config' `
    -Headers @{ accessToken = $login.accessToken } `
    -ContentType 'application/x-www-form-urlencoded' `
    -Body @{
        dataId      = 'hm-trade-service-flow-rules.json'
        groupName   = 'DEFAULT_GROUP'
        group       = 'DEFAULT_GROUP'
        namespaceId = 'public'
        type        = 'json'
        content     = $content
        username    = 'nacos'
    }
```

返回 `code=0` 且 `data=True` 代表规则已经写入 Nacos。Sentinel 命令端口采用懒初始化，刚启动时
直接访问 `8719` 可能得到空响应；应先请求一次交易接口，例如
`http://127.0.0.1:10010/voucher/list/1`，再访问
`http://127.0.0.1:8719/getRules?type=flow`，确认返回的规则不为空。默认阈值为每秒 1000 次。
Sentinel Dashboard 主要用于查看实时指标，未启动 Dashboard 不影响已加载规则在客户端执行。
从本机进程切换为容器后，旧实例可能短暂显示为不健康，等待控制台自动清理即可。

项目使用 Nacos Client `3.0.3`，Compose 中的 Nacos Server 也固定为 `3.0.3`，避免旧版
Nacos Server `2.1.0` 出现配置已经保存、客户端订阅却返回空内容的问题。

## 秒杀一致性

Lua 在 Redis 中原子完成库存校验、一人一单、库存预扣和待发布订单记录。交易服务优先发布到 RabbitMQ；如果 confirm 超时或 Broker 暂时不可用，则保留预扣资格并由定时任务重新投递，避免“预扣成功但消息丢失”和确认结果未知时错误回滚。消费者异步落库，数据库条件更新防止超卖，`user_id + voucher_id` 唯一索引保证最终幂等。消费失败最多重试三次，仍失败时通过 Lua 按订单幂等回补 Redis 资格，并将原消息写入 `trade.order.failed.queue` 供排查。

点赞关系新增了 `tb_blog_liked` 唯一表。新建 MySQL 数据卷会自动执行迁移；已有数据卷需要手动执行一次：

```powershell
Get-Content -Raw deploy/mysql/add_blog_like_table.sql |
    docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" dian-ping'
```
