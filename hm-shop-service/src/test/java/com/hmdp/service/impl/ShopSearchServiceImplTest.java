package com.hmdp.service.impl;

import com.hmdp.document.ShopDocument;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopSearchServiceImplTest {

    @Test
    void initializesMissingIndexWithExistingShops() {
        ElasticsearchOperations elasticsearchOperations = mock(ElasticsearchOperations.class);
        IndexOperations indexOperations = mock(IndexOperations.class);
        ShopMapper shopMapper = mock(ShopMapper.class);
        ShopSearchServiceImpl shopSearchService = new ShopSearchServiceImpl();
        ReflectionTestUtils.setField(shopSearchService, "elasticsearchOperations", elasticsearchOperations);
        ReflectionTestUtils.setField(shopSearchService, "shopMapper", shopMapper);
        when(elasticsearchOperations.indexOps(ShopDocument.class)).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(false);
        when(indexOperations.createWithMapping()).thenReturn(true);
        when(shopMapper.selectList(null)).thenReturn(List.of(new Shop().setId(1L).setName("test shop")));

        long count = shopSearchService.initializeIfAbsent();

        assertEquals(1L, count);
        verify(elasticsearchOperations).save(anyList());
    }
}
