package com.hmdp.ai.customer;

import com.hmdp.dto.Result;


public interface IAiCustomerService {

    Result chat(AiChatRequest request);
}
