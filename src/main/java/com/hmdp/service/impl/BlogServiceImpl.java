package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * 探店笔记业务实现
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发布探店笔记，并把新笔记推送到粉丝的 Redis 收件箱。
     *
     * @param blog 探店笔记内容
     * @return 新增笔记id
     */
    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        // 发布人必须使用后端登录态，避免前端伪造 userId。
        blog.setUserId(user.getId());
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("发布笔记失败");
        }

        // 查询作者的粉丝，把新笔记 id 写入每个粉丝的收件箱 ZSet。
        List<Follow> fans = followService.query().eq("follow_user_id", user.getId()).list();
        if (!fans.isEmpty()) {
            long now = System.currentTimeMillis();
            for (Follow fan : fans) {
                stringRedisTemplate.opsForZSet().add(FEED_KEY + fan.getUserId(), blog.getId().toString(), now);
            }
        }
        return Result.ok(blog.getId());
    }

    /**
     * 查询笔记详情，并补齐详情页需要展示的作者和点赞状态。
     *
     * @param id 探店笔记id
     * @return 探店笔记详情
     */
    @Override
    public Result queryBlogById(Long id) {
        // 详情页主数据来自 tb_blog，不存在时返回业务失败，避免前端渲染空对象出现 NaN
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        fillBlogInfo(blog);
        return Result.ok(blog);
    }

    /**
     * 当前用户点赞或取消点赞同一篇笔记。
     *
     * @param id 探店笔记id
     * @return 操作结果
     */
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // Redis 中没有当前用户，表示本次是点赞；数据库计数和 Redis 点赞集合保持同一业务方向
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // Redis 中已有当前用户，表示本次是取消点赞
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询笔记最近点赞用户，用于详情页头像列表展示。
     *
     * @param id 探店笔记id
     * @return 最近点赞用户列表
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        Set<String> userIds = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (userIds == null || userIds.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }

        List<UserDTO> users = new ArrayList<>(userIds.size());
        for (String userId : userIds) {
            // 点赞列表只需要头像和昵称，避免把密码等用户实体字段返回给前端
            User user = userService.getById(Long.valueOf(userId));
            if (user == null) {
                continue;
            }
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            users.add(dto);
        }
        return Result.ok(users);
    }

    /**
     * 分页查询当前登录用户发布的笔记，并补充点赞状态。
     *
     * @param current 页码
     * @return 当前用户的笔记列表
     */
    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::fillBlogInfo);
        return Result.ok(records);
    }

    /**
     * 分页查询热门笔记，并给每条记录补充作者信息和当前用户点赞状态。
     *
     * @param current 页码
     * @return 热门笔记列表
     */
    /**
     * 分页查询指定用户发布的笔记，博主主页使用。
     *
     * @param userId 用户id
     * @param current 页码
     * @return 指定用户的笔记列表
     */
    @Override
    public Result queryBlogByUserId(Long userId, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", userId)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::fillBlogInfo);
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::fillBlogInfo);
        return Result.ok(records);
    }

    /**
     * 查询当前用户关注的人发布的笔记，供个人主页关注 tab 滚动加载。
     *
     * @param max 上一页返回的最小时间戳，首次查询前端会传当前时间戳
     * @param offset 同时间戳偏移量，避免多条笔记分数相同导致重复查询
     * @return 滚动分页结果
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String key = FEED_KEY + userId;

        // 从当前用户收件箱按时间倒序取一页，max 是上一页返回的最小时间戳。
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, SystemConstants.MAX_PAGE_SIZE);
        if (tuples == null || tuples.isEmpty()) {
            // 老用户已经关注过别人时，Redis 收件箱可能还没有历史笔记，先回填一次再查。
            backfillFeedFromFollowBlogs(userId);
            tuples = stringRedisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(key, 0, max, offset, SystemConstants.MAX_PAGE_SIZE);
            if (tuples == null || tuples.isEmpty()) {
                return Result.ok(emptyScrollResult());
            }
        }

        List<Long> ids = new ArrayList<>(tuples.size());
        long minTime = 0L;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }

        // 批量查询笔记后用 FIELD 保持 Redis 收件箱中的时间顺序。
        String idStr = ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        List<Blog> records = query()
                .in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")")
                .list();
        records.forEach(this::fillBlogInfo);

        ScrollResult result = new ScrollResult();
        result.setList(records);
        result.setMinTime(minTime);
        result.setOffset(os);
        return Result.ok(result);
    }

    /**
     * 将当前用户已关注博主的历史笔记补进收件箱，解决接入推模式前已有关注关系看不到内容的问题。
     *
     * @param userId 当前登录用户id
     */
    private void backfillFeedFromFollowBlogs(Long userId) {
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        if (follows.isEmpty()) {
            return;
        }
        List<Long> followUserIds = follows.stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toList());
        List<Blog> blogs = query()
                .in("user_id", followUserIds)
                .orderByDesc("create_time")
                .last("LIMIT 200")
                .list();
        if (blogs.isEmpty()) {
            return;
        }

        String key = FEED_KEY + userId;
        for (Blog blog : blogs) {
            // score 使用笔记创建时间，保证历史数据与新推送数据都能按发布时间排序。
            long score = blog.getCreateTime() == null
                    ? System.currentTimeMillis()
                    : blog.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), score);
        }
    }

    /**
     * 统一构造空滚动结果，避免前端对 list 做 forEach 时空指针。
     *
     * @return 空滚动分页结果
     */
    private ScrollResult emptyScrollResult() {
        ScrollResult result = new ScrollResult();
        result.setList(new ArrayList<>());
        result.setMinTime(0L);
        result.setOffset(0);
        return result;
    }

    /**
     * 统一补充列表和详情页都需要的展示字段。
     *
     * @param blog 探店笔记
     */
    private void fillBlogInfo(Blog blog) {
        queryBlogUser(blog);
        isBlogLiked(blog);
    }

    /**
     * 补充笔记作者昵称和头像，前端直接使用这两个展示字段。
     *
     * @param blog 探店笔记
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        if (user == null) {
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断当前登录用户是否点赞过该笔记。
     *
     * @param blog 探店笔记
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }
}
