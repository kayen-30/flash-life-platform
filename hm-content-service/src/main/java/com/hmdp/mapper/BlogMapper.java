package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface BlogMapper extends BaseMapper<Blog> {

    @Insert("INSERT IGNORE INTO tb_blog_liked(blog_id, user_id) VALUES(#{blogId}, #{userId})")
    int insertBlogLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Delete("DELETE FROM tb_blog_liked WHERE blog_id = #{blogId} AND user_id = #{userId}")
    int deleteBlogLike(@Param("blogId") Long blogId, @Param("userId") Long userId);

    @Update("UPDATE tb_blog SET liked = liked + 1 WHERE id = #{blogId}")
    int incrementLiked(@Param("blogId") Long blogId);

    @Update("UPDATE tb_blog SET liked = liked - 1 WHERE id = #{blogId} AND liked > 0")
    int decrementLiked(@Param("blogId") Long blogId);
}
