package com.hmdp.service;

import com.hmdp.dto.AiChatRequest;
import com.hmdp.dto.Result;

public interface IAiCustomerService {

    Result chat(AiChatRequest request);
}
