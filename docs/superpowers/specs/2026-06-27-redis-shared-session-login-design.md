# Redis Shared Session Login Design

**Goal**

将当前基于 `HttpSession` 的短信验证码登录改造成基于 Redis 的共享 session 登录，使用 token 作为登录凭证，并通过拦截器完成用户信息续期与线程上下文注入。

**Current State**

- `UserServiceImpl` 通过 `HttpSession` 保存短信验证码和登录用户
- `LoginInterceptor` 通过 `HttpSession` 校验登录状态，并把用户写入 `UserHolder`
- `UserController` 的登录、登出、发送验证码接口仍依赖 `HttpSession`
- 项目中已经存在 `RedisConstants`，包含登录验证码与登录用户相关 key 和 TTL 常量

**Target Design**

1. 验证码存储
- `sendCode` 使用 `StringRedisTemplate` 将验证码写入 Redis
- Redis key 格式为 `login:code:{phone}`
- 过期时间使用 `RedisConstants.LOGIN_CODE_TTL`

2. 登录态存储
- `login` 从 Redis 读取验证码并校验
- 校验成功后查询用户，不存在则创建新用户
- 登录成功生成随机 token
- 将 `UserDTO` 转为 `Map<String, Object>` 后写入 Redis Hash
- Redis key 格式为 `login:token:{token}`
- 过期时间使用 `RedisConstants.LOGIN_USER_TTL`
- 登录接口返回 token，前端后续请求通过 `authorization` 请求头携带

3. 请求鉴权与续期
- 新增 `RefreshTokenInterceptor`
- 对所有请求执行：
  - 从请求头读取 `authorization`
  - 如果没有 token，直接放行
  - 如果有 token，则从 Redis 读取用户 Hash
  - 如果读取到用户，则转为 `UserDTO` 并保存到 `UserHolder`
  - 刷新对应 token 的 TTL
  - 请求完成后清理 `UserHolder`
- 保留 `LoginInterceptor` 作为需要登录接口的兜底拦截器
- `LoginInterceptor` 不再访问 `HttpSession`，而是只判断 `UserHolder` 中是否存在用户

4. Web 拦截器顺序
- `RefreshTokenInterceptor` 注册到所有路径，并设置更高优先级
- `LoginInterceptor` 继续拦截需要登录的接口，并保留原有排除路径
- 顺序要求：
  - 先执行 `RefreshTokenInterceptor`，确保 `UserHolder` 先被填充
  - 再执行 `LoginInterceptor`，只负责判断是否已登录

5. 控制器接口调整
- `sendCode` 去掉 `HttpSession` 参数
- `login` 去掉 `HttpSession` 参数
- `/me` 继续从 `UserHolder` 获取当前用户
- `/logout` 从请求头获取 token 并删除 Redis 中对应 key，不再调用 `session.invalidate()`

**Data Flow**

1. 发送验证码
- 客户端提交手机号
- 服务端校验手机号格式
- 服务端生成验证码并写入 Redis
- 返回成功响应

2. 登录
- 客户端提交手机号和验证码
- 服务端从 Redis 获取验证码并校验
- 查询或创建用户
- 生成 token
- 将用户信息存入 Redis Hash
- 返回 token

3. 访问受保护接口
- 客户端请求头携带 `authorization: token`
- `RefreshTokenInterceptor` 从 Redis 取用户并写入 `UserHolder`
- `LoginInterceptor` 校验 `UserHolder` 是否有用户
- 控制器或业务代码通过 `UserHolder.getUser()` 使用当前用户

4. 登出
- 客户端调用登出接口并携带 token
- 服务端删除 Redis 中对应登录态
- 返回成功响应

**Files To Modify**

- `src/main/java/com/hmdp/service/IUserService.java`
- `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- `src/main/java/com/hmdp/controller/UserController.java`
- `src/main/java/com/hmdp/utils/LoginInterceptor.java`
- `src/main/java/com/hmdp/config/WebMvcConfig.java`

**Files To Add**

- `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`

**Implementation Notes**

- `StringRedisTemplate` 统一用于验证码和登录用户读写
- `UserDTO` 写入 Redis Hash 前需要转成字符串字段，避免类型不兼容
- `BeanUtil.fillBeanWithMap()` 适合将 Redis Hash 转回 `UserDTO`
- 需要保证 `UserHolder.removeUser()` 在请求结束时被调用，避免线程复用导致串用户
- 如果前端已经按课程模板实现，通常会将 token 写入 localStorage，并通过 axios 请求头自动携带

**Out of Scope**

- 不引入新的测试类
- 不改造业务接口为无状态 JWT
- 不扩展额外的权限体系
- 不对前端页面做结构性重写，仅在必要时验证 token 传递是否匹配后端实现

**Verification**

- 启动 Redis、MySQL 和应用
- 调用 `/user/code` 后检查 Redis 中存在 `login:code:{phone}`
- 调用 `/user/login` 后响应中返回 token
- 检查 Redis 中存在 `login:token:{token}` 哈希数据
- 使用带 `authorization` 的请求访问 `/user/me`，应返回当前用户
- 清除或伪造 token 后访问受保护接口，应返回 401
- 调用 `/user/logout` 后，Redis 中对应 token 数据应被删除
