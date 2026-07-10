package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

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
        // 学习环境暂未接入短信服务，通过 DEBUG 日志获取验证码；生产环境应关闭 DEBUG 日志。
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
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    /**
     * 将当天签到写入 Redis bitmap，每个月一个 key，offset 从 0 表示当月 1 号。
     */
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        String key = buildSignKey(userId, now);
        int offset = now.getDayOfMonth() - 1;

        // 同一天重复签到仍然写 true，bitmap 天然保持幂等。
        stringRedisTemplate.opsForValue().setBit(key, offset, true);
        return Result.ok();
    }

    /**
     * 统计本月截至今天的连续签到天数，从今天向前遇到第一个 0 即停止。
     */
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDateTime now = LocalDateTime.now();
        int dayOfMonth = now.getDayOfMonth();
        String key = buildSignKey(userId, now);

        // 取出本月 1 号到今天的签到位，最低位代表今天，便于从后往前统计连续 1。
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null) {
            return Result.ok(0);
        }

        long num = result.get(0);
        int count = 0;
        while ((num & 1) == 1) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }

    private UserDTO toUserDTO(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());
        return userDTO;
    }

    private String buildSignKey(Long userId, LocalDateTime time) {
        return USER_SIGN_KEY + userId + ":" + time.format(DateTimeFormatter.ofPattern("yyyyMM"));
    }
}
