# Shop Cache By ID Design

**Goal**

Add Redis caching to the `/shop/{id}` query path so shop detail requests read from cache first, fall back to MySQL on cache miss, and write the DB result back into Redis.

**Current State**

- `ShopController.queryShopById()` directly calls `shopService.getById(id)`.
- `IShopService` and `ShopServiceImpl` do not expose a dedicated shop-detail query method.
- `RedisConstants` already defines `CACHE_SHOP_KEY` and `CACHE_SHOP_TTL`.
- The project already uses `StringRedisTemplate` for login-related Redis reads and writes.

**Target Design**

1. Controller delegation
- `ShopController.queryShopById()` will stop querying the database directly.
- The controller will delegate to a new `shopService.queryById(id)` method so cache logic stays in the service layer.

2. Service query flow
- Build the Redis key with `CACHE_SHOP_KEY + id`.
- Read the cached JSON string from Redis first.
- If Redis returns a non-blank value, deserialize it into `Shop` and return it immediately.
- If Redis has no value, query MySQL with `getById(id)`.
- If MySQL returns a shop, serialize it to JSON, write it back to Redis with `CACHE_SHOP_TTL`, then return the shop.
- If MySQL returns `null`, return `Result.fail("店铺不存在！")`.

3. Scope boundary
- This change only covers the read path for `/shop/{id}`.
- The update path keeps its current behavior and does not clear cache in this step.
- Missing-shop results are not cached in this step.
- No mutex lock, logical expiration, or cache rebuild strategy is included in this step.

**Data Flow**

1. Client requests `GET /shop/{id}`.
2. Controller calls `IShopService.queryById(id)`.
3. Service checks Redis key `cache:shop:{id}`.
4. If cached JSON exists, service returns the deserialized `Shop`.
5. If cache is missing, service queries MySQL.
6. If the shop exists, service writes JSON back to Redis with TTL and returns the shop.
7. If the shop does not exist, service returns a failure result.

**Files To Modify**

- `src/main/java/com/hmdp/controller/ShopController.java`
- `src/main/java/com/hmdp/service/IShopService.java`
- `src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`

**Implementation Notes**

- Use `StringRedisTemplate` in `ShopServiceImpl` through Spring injection.
- Use Hutool JSON utilities already available in the project dependencies to serialize and deserialize `Shop`.
- Keep controller code thin and move cache decisions into the service layer.
- Add concise Chinese comments in Java where business flow or non-obvious framework behavior needs explanation.

**Out of Scope**

- Cache penetration protection with empty-value caching
- Cache breakdown protection with distributed lock
- Logical expiration rebuild
- Cache invalidation on shop update
- New automated tests or test skeletons

**Verification**

- Start MySQL, Redis, and the Spring Boot app.
- Call `GET /shop/{id}` for an existing shop and confirm the response is successful.
- Check Redis for key `cache:shop:{id}` after the first request.
- Call the same endpoint again and confirm the response still succeeds with the same data.
- Call `GET /shop/{id}` for a missing shop and confirm the response returns `success=false` with `errorMsg=店铺不存在！`.
