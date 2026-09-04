# 项目迁移说明

## 推荐方式：创建干净环境

1. 在新电脑安装并启动 Docker Desktop。
2. 将 U 盘中的 `project` 目录复制到新电脑本地磁盘。
3. 双击项目根目录的 `start.cmd`。
4. 浏览器访问 `http://localhost:8080`。

仓库包含前端、后端和 MySQL 初始化数据。第一次启动会创建新的 Docker 数据卷，不需要安装本机
MySQL、Redis、RabbitMQ、Nacos、JDK 或 Maven。

## 恢复本机当前 MySQL 数据

先启动 MySQL：

```powershell
docker compose up -d mysql
```

将 U 盘备份复制到容器并恢复：

```powershell
docker cp ..\backups\mysql\dian-ping-full.sql.gz hmdp-mysql:/tmp/dian-ping-full.sql.gz
docker exec hmdp-mysql sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD gzip -dc /tmp/dian-ping-full.sql.gz | mysql -uroot'
```

恢复完成后启动全部服务：

```powershell
docker compose up -d --build
```

## 其他运行数据

U 盘 `backups` 目录还包含：

- `redis`：登录会话、缓存、秒杀库存和待发布订单。
- `milvus`：AI 知识向量和长期会话记忆。
- `rabbitmq/rabbitmq-definitions.json`：RabbitMQ 用户、交换机、队列和绑定定义。
- `nacos`：Nacos 持久化数据。

这些原始数据目录用于完整留档。跨电脑恢复时应先创建对应 Docker volume，再在相关容器停止状态下写入，
避免覆盖正在运行的数据。通常只恢复 MySQL 即可，Redis 缓存和登录会话可以重新生成。

## AI 向量库维度迁移

当前 AI Embedding 和 Milvus 默认使用 `1536` 维。若已有环境使用旧的 `1024` 维 collection，先停止
AI 服务，在 Attu 中删除 `shop_knowledge`、`conversation_memory`，并删除 Redis 中对应的导入标记
`ai:milvus:knowledge:imported:<collection>:<version>`，再启动 AI 服务触发重新导入。删除 collection
属于一次性数据迁移操作，执行前应确认已有向量数据可以重建。

## 离线镜像

如果 U 盘根目录存在 `docker-images.tar`，可先执行：

```powershell
docker load -i ..\docker-images.tar
docker compose up -d --no-build
```

项目中的 `.env` 可能包含本机密码或 API Key，U 盘应妥善保管，不要提交到公开仓库。
