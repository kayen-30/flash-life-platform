package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
@Tag(name = "文件上传接口", description = "探店博客图片上传和删除接口")
public class UploadController {

    @PostMapping("blog")
    @Operation(summary = "上传博客图片", description = "上传探店博客图片，返回可保存到博客内容中的图片相对路径。")
    public Result uploadImage(@Parameter(description = "图片文件") @RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName));
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 删除上传目录内的博客图片，拒绝绝对路径和跨目录访问。
     */
    @DeleteMapping("/blog/delete")
    @Operation(summary = "删除博客图片", description = "根据图片相对路径删除已上传的博客图片。")
    public Result deleteBlogImg(@Parameter(description = "图片相对路径", example = "/blogs/1/2/demo.jpg") @RequestParam("name") String filename) {
        if (StrUtil.isBlank(filename)) {
            return Result.fail("错误的文件名称");
        }

        Path uploadRoot = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR).toAbsolutePath().normalize();
        String relativeFilename = StrUtil.removePrefix(filename.replace('\\', '/'), "/");
        Path target = uploadRoot.resolve(relativeFilename).normalize();

        // 规范化后必须仍位于上传根目录，防止通过 ../ 删除任意文件。
        if (!target.startsWith(uploadRoot) || !Files.isRegularFile(target)) {
            return Result.fail("错误的文件名称");
        }

        try {
            Files.delete(target);
            return Result.ok();
        } catch (IOException e) {
            log.warn("删除博客图片失败，文件：{}", target, e);
            return Result.fail("删除文件失败");
        }
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 判断目录是否存在
        File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }
}
