CREATE TABLE IF NOT EXISTS `tb_blog_liked` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `blog_id` bigint UNSIGNED NOT NULL COMMENT '笔记id',
  `user_id` bigint UNSIGNED NOT NULL COMMENT '点赞用户id',
  `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_blog_liked_blog_user` (`blog_id`, `user_id`),
  KEY `idx_blog_liked_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记点赞关系';
