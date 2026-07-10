package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class UploadControllerTest {

    @Test
    void deleteBlogImgRejectsPathTraversal() {
        UploadController controller = new UploadController();

        Result result = controller.deleteBlogImg("../application.yaml");

        assertFalse(result.getSuccess());
    }
}
