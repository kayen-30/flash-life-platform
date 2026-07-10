package com.hmdp.utils;

public class SystemConstants {
    // 图片要保存到当前 8080 端口 nginx 的静态资源目录，否则前端访问 /imgs/** 会 404
    public static final String IMAGE_UPLOAD_DIR = "D:\\develop\\nginx-1.18.0-hmdp\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
}
