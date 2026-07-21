package com.hmdp.utils;

/**
 * 内容社区服务独占的点赞、关注和 Feed key。
 */
public final class RedisConstants {
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FOLLOW_KEY = "follows:";
    public static final String FEED_KEY = "feed:";

    private RedisConstants() {
    }
}
