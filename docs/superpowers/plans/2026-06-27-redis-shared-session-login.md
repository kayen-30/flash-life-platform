# Redis Shared Session Login Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将基于 `HttpSession` 的登录改造成基于 Redis 的共享 session 登录，并加入 token 自动续期拦截器。

**Architecture:** 使用 `StringRedisTemplate` 保存短信验证码和登录用户信息，登录成功后向前端返回 token。请求进入时通过 `RefreshTokenInterceptor` 从 Redis 恢复当前用户到 `UserHolder` 并刷新 TTL，再由 `LoginInterceptor` 判断是否允许访问受保护接口。

**Tech Stack:** Spring Boot 2.3, Spring MVC Interceptor, Spring Data Redis, MyBatis-Plus, Hutool

---

### Task 1: 改造用户服务为 Redis 登录

**Files:**
- Modify: `src/main/java/com/hmdp/service/IUserService.java`
- Modify: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- Verify: `mvn -q -DskipTests compile`

- [ ] **Step 1: 实现 Redis 版验证码和登录逻辑**

```java
public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);
}
```

```java
@Resource
private StringRedisTemplate stringRedisTemplate;

@Override
public Result sendCode(String phone) {
    if (RegexUtils.isPhoneInvalid(phone)) {
        return Result.fail("手机号格式错误");
    }
    String code = RandomUtil.randomNumbers(6);
    stringRedisTemplate.opsForValue().set(
            LOGIN_CODE_KEY + phone,
            code,
            LOGIN_CODE_TTL,
            TimeUnit.MINUTES
    );
    log.debug("发送短信验证码成功，验证码：{}", code);
    return Result.ok();
}

@Override
public Result login(LoginFormDTO loginForm) {
    String phone = loginForm.getPhone();
    if (RegexUtils.isPhoneInvalid(phone)) {
        return Result.fail("手机号格式错误");
    }
    String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
    if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
        return Result.fail("验证码错误");
    }
    User user = getOne(new QueryWrapper<User>().eq("phone", phone));
    if (user == null) {
        user = createUserWithPhone(phone);
    }
    String token = UUID.randomUUID().toString(true);
    UserDTO userDTO = toUserDTO(user);
    Map<String, Object> userMap = BeanUtil.beanToMap(
            userDTO,
            new HashMap<>(),
            CopyOptions.create()
                    .setIgnoreNullValue(true)
                    .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? null : fieldValue.toString())
    );
    String tokenKey = LOGIN_USER_KEY + token;
    stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
    stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
    return Result.ok(token);
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn -q -DskipTests compile`  
Expected: 编译成功，没有接口签名或 Redis 依赖相关错误。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/service/IUserService.java src/main/java/com/hmdp/service/impl/UserServiceImpl.java
git commit -m "feat: move login state to redis"
```

### Task 2: 改造控制器为 token 登录接口

**Files:**
- Modify: `src/main/java/com/hmdp/controller/UserController.java`
- Verify: `mvn -q -DskipTests compile`

- [ ] **Step 1: 去掉 HttpSession 依赖并改造登出**

```java
@PostMapping("code")
public Result sendCode(@RequestParam("phone") String phone) {
    return userService.sendCode(phone);
}

@PostMapping("/login")
public Result login(@RequestBody LoginFormDTO loginForm) {
    return userService.login(loginForm);
}

@PostMapping("/logout")
public Result logout(HttpServletRequest request) {
    String token = request.getHeader("authorization");
    return userService.logout(token);
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn -q -DskipTests compile`  
Expected: 编译成功，控制器不再引用 `HttpSession`。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/controller/UserController.java
git commit -m "refactor: remove session from user controller"
```

### Task 3: 增加登出能力并引入 Redis 续期拦截器

**Files:**
- Modify: `src/main/java/com/hmdp/service/IUserService.java`
- Modify: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- Create: `src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`
- Verify: `mvn -q -DskipTests compile`

- [ ] **Step 1: 增加登出服务并实现刷新 token 逻辑**

```java
Result logout(String token);
```

```java
@Override
public Result logout(String token) {
    if (StrUtil.isNotBlank(token)) {
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
    }
    return Result.ok();
}
```

```java
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        String tokenKey = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        if (userMap.isEmpty()) {
            return true;
        }
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserHolder.removeUser();
    }
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn -q -DskipTests compile`  
Expected: 编译成功，新增拦截器可被 Spring MVC 配置使用。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/service/IUserService.java src/main/java/com/hmdp/service/impl/UserServiceImpl.java src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java
git commit -m "feat: add redis token refresh interceptor"
```

### Task 4: 重写登录拦截器并注册拦截器顺序

**Files:**
- Modify: `src/main/java/com/hmdp/utils/LoginInterceptor.java`
- Modify: `src/main/java/com/hmdp/config/WebMvcConfig.java`
- Verify: `mvn -q -DskipTests compile`

- [ ] **Step 1: 改造登录拦截器为 ThreadLocal 校验并注册顺序**

```java
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
```

```java
@Resource
private StringRedisTemplate stringRedisTemplate;

@Bean
public RefreshTokenInterceptor refreshTokenInterceptor() {
    return new RefreshTokenInterceptor(stringRedisTemplate);
}

@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(refreshTokenInterceptor()).addPathPatterns("/**").order(0);
    registry.addInterceptor(loginInterceptor())
            .excludePathPatterns(
                    "/user/code",
                    "/user/login",
                    "/user/info/**",
                    "/shop/**",
                    "/shop-type/**",
                    "/voucher/**",
                    "/upload/**",
                    "/blog/hot"
            ).order(1);
}
```

- [ ] **Step 2: 验证编译通过**

Run: `mvn -q -DskipTests compile`  
Expected: 编译成功，MVC 配置可正常实例化两个拦截器。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/hmdp/utils/LoginInterceptor.java src/main/java/com/hmdp/config/WebMvcConfig.java
git commit -m "refactor: switch auth interceptors to redis token flow"
```

### Task 5: 进行最小联调验证

**Files:**
- Verify only: `src/main/java/com/hmdp/controller/UserController.java`
- Verify only: `src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- Verify: 手工接口验证

- [ ] **Step 1: 编译并进行手工验证**

Run: `mvn -q -DskipTests compile`  
Then manually verify:

```text
1. POST /user/code?phone=13800138000
2. 从 Redis 读取 login:code:13800138000 对应验证码
3. POST /user/login 提交 phone 和 code，确认响应 data 为 token
4. 检查 Redis 中存在 login:token:{token} 哈希
5. GET /user/me 并携带 authorization 请求头，确认返回 UserDTO
6. POST /user/logout 并携带 authorization，请求后确认 Redis 中 token key 已删除
```

Expected: token 登录链路完整可用，未登录访问受保护接口返回 401。

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/hmdp/controller/UserController.java src/main/java/com/hmdp/service/impl/UserServiceImpl.java src/main/java/com/hmdp/service/IUserService.java src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java src/main/java/com/hmdp/utils/LoginInterceptor.java src/main/java/com/hmdp/config/WebMvcConfig.java
git commit -m "feat: complete redis shared session login flow"
```
