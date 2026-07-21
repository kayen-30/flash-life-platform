package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadControllerTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void deleteBlogImgRejectsPathTraversal() {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDir", ".");

        Result result = controller.deleteBlogImg("../application.yaml");

        assertFalse(result.getSuccess());
    }

    @Test
    void uploadRejectsFileWhoseContentIsNotAnImage() {
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDir", ".");
        ReflectionTestUtils.setField(controller, "uploadMaxBytes", 5 * 1024 * 1024L);
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.jpg", "image/jpeg", "not-an-image".getBytes()
        );

        Result result = controller.uploadImage(file);

        assertFalse(result.getSuccess());
    }

    @Test
    void deleteRejectsImageOwnedByAnotherUser() throws IOException {
        Path otherUserImage = tempDir.resolve("blogs/2/demo.jpg");
        Files.createDirectories(otherUserImage.getParent());
        Files.writeString(otherUserImage, "image");
        UploadController controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDir", tempDir.toString());
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);

        Result result = controller.deleteBlogImg("/blogs/2/demo.jpg");

        assertFalse(result.getSuccess());
        assertTrue(Files.exists(otherUserImage));
    }
}
