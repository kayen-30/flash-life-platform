package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
@Tag(name = "文件上传接口", description = "探店博客图片上传和删除接口")
public class UploadController {

    private static final Set<String> ALLOWED_IMAGE_FORMATS = Set.of("jpeg", "jpg", "png", "gif");

    @Value("${hmdp.upload-dir:./data/uploads}")
    private String uploadDir;

    @Value("${hmdp.upload-max-bytes:5242880}")
    private long uploadMaxBytes;

    @PostMapping("blog")
    @Operation(summary = "上传博客图片", description = "上传探店博客图片，返回可保存到博客内容中的图片相对路径。")
    public Result uploadImage(@Parameter(description = "图片文件") @RequestParam("file") MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return Result.fail("请选择图片文件");
        }
        if (image.getSize() > uploadMaxBytes) {
            return Result.fail("图片不能超过5MB");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        try {
            String suffix = detectImageFormat(image);
            if (suffix == null) {
                return Result.fail("只支持JPEG、PNG或GIF图片");
            }
            String fileName = createNewFileName(user.getId(), suffix);
            Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path target = uploadRoot.resolve(StrUtil.removePrefix(fileName, "/")).normalize();
            Files.createDirectories(target.getParent());
            image.transferTo(target);
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

        Path uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        String relativeFilename = StrUtil.removePrefix(filename.replace('\\', '/'), "/");
        Path target = uploadRoot.resolve(relativeFilename).normalize();

        // 规范化后必须仍位于上传根目录，防止通过 ../ 删除任意文件。
        if (!target.startsWith(uploadRoot) || !Files.isRegularFile(target)) {
            return Result.fail("错误的文件名称");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Path userRoot = uploadRoot.resolve("blogs").resolve(user.getId().toString()).normalize();
        if (!target.startsWith(userRoot)) {
            return Result.fail("无权删除该图片");
        }

        try {
            Files.delete(target);
            return Result.ok();
        } catch (IOException e) {
            log.warn("删除博客图片失败，文件：{}", target, e);
            return Result.fail("删除文件失败");
        }
    }

    private String detectImageFormat(MultipartFile image) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(image.getInputStream())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            String format;
            try {
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
            } finally {
                reader.dispose();
            }
            if (!ALLOWED_IMAGE_FORMATS.contains(format)) {
                return null;
            }
            return "jpeg".equals(format) ? "jpg" : format;
        }
    }

    private String createNewFileName(Long userId, String suffix) {
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 用户 id 进入路径后，删除接口可以在不引入文件元数据表的前提下校验归属。
        return StrUtil.format("/blogs/{}/{}/{}/{}.{}", userId, d1, d2, name, suffix);
    }
}
