package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.FEED_KEY;
import static com.hmdp.utils.RedisConstants.FOLLOW_KEY;

/**
 * 用户关注业务实现
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    @Resource
    private BlogMapper blogMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 关注或取消关注目标用户，并同步维护 Redis 关注集合。
     *
     * @param followUserId 被关注用户id
     * @param isFollow 是否关注
     * @return 操作结果
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;
        if (Boolean.TRUE.equals(isFollow)) {
            Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            boolean isSuccess = true;
            if (count == 0) {
                // 数据库保存关注关系，Redis 只做高频查询加速
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                isSuccess = save(follow);
            }
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                syncFollowBlogsToFeed(userId, followUserId);
            }
        } else {
            // 取关时数据库和 Redis 都移除同一条关系，避免共同关注结果滞后
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
            stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            removeFollowBlogsFromFeed(userId, followUserId);
        }
        return Result.ok();
    }

    /**
     * 判断当前用户是否关注目标用户。
     *
     * @param followUserId 被关注用户id
     * @return 是否已关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(FOLLOW_KEY + userId, followUserId.toString());
        if (Boolean.TRUE.equals(isMember)) {
            return Result.ok(true);
        }

        // Redis 可能因重启或旧数据未同步而为空，兜底查数据库并回填集合
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        if (count > 0) {
            stringRedisTemplate.opsForSet().add(FOLLOW_KEY + userId, followUserId.toString());
        }
        return Result.ok(count > 0);
    }

    /**
     * 查询当前用户与目标用户的共同关注。
     *
     * @param followUserId 目标用户id
     * @return 共同关注用户列表
     */
    @Override
    public Result followCommons(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOW_KEY + userId;
        String key2 = FOLLOW_KEY + followUserId;

        // 历史关注关系可能还没写入 Redis，先按数据库补齐两个人的关注集合
        refreshFollowSet(userId);
        refreshFollowSet(followUserId);

        // Redis Set 交集直接得到共同关注的用户 id
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }

        List<UserDTO> commonUsers = new ArrayList<>(intersect.size());
        for (String commonUserId : intersect) {
            // 共同关注只返回主页展示需要的用户字段
            User user = userService.getById(Long.valueOf(commonUserId));
            if (user == null) {
                continue;
            }
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            commonUsers.add(dto);
        }
        return Result.ok(commonUsers);
    }

    /**
     * 查询当前用户关注数量，个人主页关注 tab 使用。
     *
     * @return 当前登录用户关注的人数
     */
    @Override
    public Result countMyFollows() {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOW_KEY + userId;

        // 页面数量以数据库为准，避免 Redis 只回填了部分历史关注时显示偏小。
        Long count = query().eq("user_id", userId).count();
        Long size = stringRedisTemplate.opsForSet().size(key);
        if (!Objects.equals(size, count)) {
            // 数量不一致说明 Redis 可能缺数据或有脏数据，按数据库重新同步集合。
            refreshFollowSet(userId);
        }
        return Result.ok(count);
    }

    /**
     * 将指定用户的关注关系回填到 Redis Set，保证旧数据也能参与交集计算。
     *
     * @param userId 用户id
     */
    private void refreshFollowSet(Long userId) {
        String key = FOLLOW_KEY + userId;
        List<Follow> follows = query().eq("user_id", userId).list();
        // 先删除旧集合再回填，避免历史脏数据影响关注数量和共同关注结果。
        stringRedisTemplate.delete(key);
        if (follows.isEmpty()) {
            return;
        }
        String[] followUserIds = follows.stream()
                .map(follow -> follow.getFollowUserId().toString())
                .toArray(String[]::new);
        stringRedisTemplate.opsForSet().add(key, followUserIds);
    }

    /**
     * 关注成功后，把被关注用户已有笔记同步到当前用户收件箱。
     *
     * @param userId 当前用户id
     * @param followUserId 被关注用户id
     */
    private void syncFollowBlogsToFeed(Long userId, Long followUserId) {
        List<Blog> blogs = blogMapper.selectList(
                new QueryWrapper<Blog>().eq("user_id", followUserId).orderByDesc("create_time")
        );
        if (blogs.isEmpty()) {
            return;
        }
        String feedKey = FEED_KEY + userId;
        for (Blog blog : blogs) {
            // 使用笔记创建时间作为分数，保证关注动态按发布时间排序。
            long score = blog.getCreateTime() == null
                    ? System.currentTimeMillis()
                    : blog.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            stringRedisTemplate.opsForZSet().add(feedKey, blog.getId().toString(), score);
        }
    }

    /**
     * 取关后从当前用户收件箱移除该博主的历史笔记。
     *
     * @param userId 当前用户id
     * @param followUserId 被取关用户id
     */
    private void removeFollowBlogsFromFeed(Long userId, Long followUserId) {
        List<Blog> blogs = blogMapper.selectList(new QueryWrapper<Blog>().eq("user_id", followUserId));
        if (blogs.isEmpty()) {
            return;
        }
        String[] blogIds = blogs.stream()
                .map(blog -> blog.getId().toString())
                .toArray(String[]::new);
        stringRedisTemplate.opsForZSet().remove(FEED_KEY + userId, (Object[]) blogIds);
    }
}
