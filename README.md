# 黑马点评微服务版

项目已经从单体拆分为 Spring Cloud 多模块应用，外部接口路径保持不变，统一通过 `hm-gateway` 访问。

## 新电脑快速启动

只需安装并启动 Docker Desktop，然后克隆仓库并双击根目录的 `start.cmd`。脚本会构建镜像、创建数据库和
启动全部服务；首次构建需要下载依赖，耗时取决于网络速度。

也可以在 PowerShell 中启动：

```powershell
git clone https://github.com/kayen-30/flash-life-platform.git
cd flash-life-platform
.\start.ps1
```

项目带有前端静态资源和 MySQL 初始数据，不要求安装本机 MySQL、Redis、RabbitMQ、Nacos、JDK 或 Maven。
`start.ps1` 首次运行会创建被 Git 忽略的 `.env`，并生成服务间与 Nacos 认证材料。AI 对话默认关闭，
因此首次启动不需要外部 API Key。

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
| Milvus Standalone | 19530 / 9091 | AI RAG 向量检索 / 健康检查 |
| RabbitMQ | 5672 / 15672 | 秒杀订单队列 / 管理台 |
| Nacos 3 | 8848 | 服务注册与配置中心 |
| Sentinel Dashboard | 8858 | 流量监控与规则查看 |
| Nginx | 8080 | 前端静态资源和 `/api` 网关代理 |
| Gateway | 10010 | 容器内服务的统一后端入口 |

Compose 发布的宿主机端口均绑定到 `127.0.0.1`，仅供本机开发使用；生产环境应通过 TLS 终结的入口网关或
负载均衡器暴露所需服务，而不是直接发布基础设施端口。Compose 中的 MySQL 和 RabbitMQ 默认账号密码同样仅
适用于这个本机开发环境，生产部署必须由密钥管理系统覆盖。

首次切换到容器前，应先停止占用 `6379` 和 `8080` 的本机进程。Docker MySQL 使用 `13306`，
不要求停止占用 `3306` 的本机 `MySQL80`。首次启动请执行 `start.ps1`，它会初始化本地 `.env` 并启动全部组件；
已有 `.env` 时可直接执行：

```powershell
docker compose up -d --build
```

MySQL 首次创建数据卷时会执行 `deploy/mysql` 下的初始化脚本；后续重启不会重复初始化。前端默认使用
仓库内的 `frontend` 目录，也可通过 `HMDP_FRONTEND_DIR` 覆盖。常用入口：

- 前端：`http://localhost:8080`
- 网关：`http://localhost:10010`
- Nacos API：`http://localhost:8848`
- Nacos 3 控制台：`http://localhost:18048`
- Sentinel Dashboard：`http://localhost:8858`，默认账号密码为 `sentinel/sentinel`
- RabbitMQ 管理台：`http://localhost:15672`，默认账号密码为 `hmdp/hmdp-rabbit`

Nacos 3 已启用认证，API、gRPC 和控制台端口只绑定本机回环地址。首次本地启动会使用官方镜像的
`nacos/nacos` 初始化账号供服务连接；在共享或生产环境中必须预先创建独立账号并通过安全的密钥管理系统
提供 `NACOS_USERNAME`、`NACOS_PASSWORD` 和 Nacos 服务端认证材料。

数据保存在 Docker named volumes 中。`docker compose down` 只停止并删除容器；不要使用
`docker compose down -v`，除非确认可以删除 MySQL、Redis、Milvus、RabbitMQ 和 Nacos 数据。

Java 服务在 Compose 网络中通过 `mysql`、`redis`、`milvus`、`rabbitmq`、`nacos` 和
`sentinel-dashboard` 容器名通信，业务服务端口不会发布到宿主机。查看服务状态和日志：

```powershell
docker compose ps
docker compose logs -f gateway trade-service
```

需要在 IDE 中调试 Java 服务时，可以只启动基础设施，再分别运行本地模块：

```powershell
docker compose up -d mysql redis milvus rabbitmq nacos sentinel-dashboard frontend
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

请使用 `start.ps1` 初始化被 Git 忽略的 `.env` 后再运行 Compose 或从 IDE 启动服务。该脚本只在缺少值时
生成本地凭证，不会覆盖已有配置。常用环境变量：

- `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`
- `REDIS_HOST`、`REDIS_PORT`
- `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD`
- `NACOS_ADDR`、`NACOS_ENABLED`、`NACOS_USERNAME`、`NACOS_PASSWORD`
- `NACOS_AUTH_TOKEN`、`NACOS_AUTH_IDENTITY_KEY`、`NACOS_AUTH_IDENTITY_VALUE`：Nacos 服务端认证材料
- `HMDP_INTERNAL_TOKEN`：Feign 内部接口与网关用户上下文的共享可信凭证
- `HMDP_ADMIN_USER_IDS`：允许维护店铺和优惠券的用户 id，多个值使用逗号分隔
- `MILVUS_ENABLED`、`MILVUS_HOST`、`MILVUS_PORT`：Milvus 向量库开关和地址，默认 `127.0.0.1:19530`；Compose 内部使用 `milvus:19530`
- `MILVUS_KNOWLEDGE_COLLECTION`、`MILVUS_CONVERSATION_COLLECTION`：知识库和长期会话 collection 名称
- `MILVUS_AUTO_IMPORT`、`MILVUS_EMBEDDING_DIMENSION`：初始知识导入开关和向量维度，默认维度为 `1536`
- `HMDP_AI_RAG_MEMORY_SHORT_TERM_ROUNDS`、`HMDP_AI_RAG_MEMORY_TOP_K`：Redis 短期轮数和 Milvus 长期记忆条数
- `HMDP_AI_RAG_VECTOR_ENABLED`：开启向量召回；默认关闭时使用内置关键词检索
- `HMDP_AI_RAG_VECTOR_CANDIDATE_TOP_K`、`HMDP_AI_RAG_VECTOR_TOP_K`：向量候选数和最终知识数，默认 `10` / `3`
- `OPENAI_EMBEDDING_API_KEY`、`OPENAI_EMBEDDING_BASE_URL`、`OPENAI_EMBEDDING_MODEL`：Embedding 专用 OpenAI 兼容接口配置
- `COHERE_API_KEY`、`COHERE_RERANK_ENABLED`：可选的 Cohere 多语言 Rerank，默认关闭；精排失败会回退原召回顺序
- `SENTINEL_DASHBOARD`：Sentinel 控制台地址，默认 `127.0.0.1:8858`
- `HMDP_UPLOAD_DIR`：内容图片目录，多实例部署应替换为 MinIO/S3
- `OPENAI_API_KEY` 或 `DEEPSEEK_API_KEY`、`LANGCHAIN4J_ENABLED`：启用 LangChain4j 客服 Agent
- `HMDP_AI_CHAT_RATE_LIMIT`、`HMDP_AI_RECOMMEND_RATE_LIMIT`：单用户每分钟模型调用上限

AI 对话默认关闭。启用 DeepSeek 或其他 OpenAI 兼容模型时，设置 `LANGCHAIN4J_ENABLED=true` 和对应的
`OPENAI_API_KEY`（或 `DEEPSEEK_API_KEY`）。开启向量 RAG 时还需启动 Milvus，并设置
`HMDP_AI_RAG_VECTOR_ENABLED=true`；开启 Cohere 中文精排时额外设置 `COHERE_RERANK_ENABLED=true` 和
`COHERE_API_KEY`。任一 RAG 组件异常都会降级到关键词检索或纯工具调用，不阻断客服主链路。

Embedding 可以使用独立的 OpenAI 兼容中转接口，不会改变聊天模型配置。设置
`LANGCHAIN4J_EMBEDDING_ENABLED=true`、`HMDP_AI_RAG_VECTOR_ENABLED=true`，并填写
`OPENAI_EMBEDDING_API_KEY`、`OPENAI_EMBEDDING_BASE_URL` 和支持的 Embedding 模型名。

普通 Redis 仅保存最近几轮短期会话和限流状态，Milvus 保存平台知识与长期语义记忆，因此不需要 Redis Stack。
需要查看 collection 时可执行 `docker compose --profile tools up -d attu`，然后访问 `http://localhost:3000`。

如果从旧版本的 `1024` 维向量升级，必须先停止 AI 服务，在 Attu 中删除 `shop_knowledge` 和
`conversation_memory` 两个旧 collection，并删除对应的 Redis 导入标记后再启动 AI 服务；否则旧 schema
无法接收新的 `1536` 维向量。当前 Compose 的 Milvus 容器名为 `hmdp-milvus`。

AI 客服请求示例：

```powershell
curl.exe -X POST http://localhost:8080/api/ai/customer-service/chat `
  -H "Content-Type: application/json" `
  -H "Authorization: $env:AI_TEST_TOKEN" `
  -d '{"message":"平台有什么优惠活动？"}'
```

成功响应的 `data.sources` 返回本次命中的友好知识来源，例如 `平台 FAQ - 优惠券说明`。

`X-Internal-Token` 不是用户登录 Token，而是 Feign 调用 `/internal/**` 接口时自动携带的服务间共享凭证。
网关会先清除客户端伪造的用户和内部凭证头，只在登录态校验成功后重新注入用户身份与该凭证；业务服务缺少
可信凭证时不会恢复 `X-User-Id`。所有服务必须配置相同的 `HMDP_INTERNAL_TOKEN`，并通过密钥管理系统轮换它。

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

返回 `code=0` 且 `data=True` 代表规则已经写入 Nacos。默认阈值为每秒 1000 次。
Sentinel Dashboard 主要用于查看实时指标，未启动 Dashboard 不影响已加载规则在客户端执行。
从本机进程切换为容器后，旧实例可能短暂显示为不健康，等待控制台自动清理即可。

项目使用 Nacos Client `3.0.3`，Compose 中的 Nacos Server 也固定为 `3.0.3`，避免旧版
Nacos Server `2.1.0` 出现配置已经保存、客户端订阅却返回空内容的问题。

## 秒杀一致性

Lua 在 Redis 中原子完成库存校验、一人一单、库存预扣和待发布订单记录。首次 RabbitMQ 投递由独立线程池异步执行；投递失败或进程中断时保留预扣资格，并由定时任务重新投递。消费者异步落库，数据库条件更新防止超卖，`user_id + voucher_id + active_order` 唯一索引保证同一用户只能有一张未取消订单。订单落库后会发送 3 分钟延迟取消消息；当前项目尚未实现支付，超时后订单会取消并同时回补 MySQL 与 Redis，用户可以重新抢购。取消和回滚 Lua 都校验 `orderId` 归属，迟到的旧消息不会释放新订单资格。

已有 MySQL 数据卷不会自动执行新增迁移。升级到允许取消后重新抢购的版本时，执行一次下面的幂等脚本；重复执行不会再次添加列或索引：

```powershell
Get-Content -Raw deploy/mysql/add_voucher_order_unique_index.sql |
    docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" dian-ping'
```

点赞关系新增了 `tb_blog_liked` 唯一表。新建 MySQL 数据卷会自动执行迁移；已有数据卷需要手动执行一次：

```powershell
Get-Content -Raw deploy/mysql/add_blog_like_table.sql |
    docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" dian-ping'
```

商铺全文检索需要的 `tb_shop.description` 字段由 `deploy/mysql/add_shop_description.sql` 幂等迁移；已有数据卷可手动执行一次：

```powershell
Get-Content -Raw deploy/mysql/add_shop_description.sql |
    docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" dian-ping'
```
