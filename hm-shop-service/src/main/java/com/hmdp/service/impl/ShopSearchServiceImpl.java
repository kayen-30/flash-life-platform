package com.hmdp.service.impl;

import com.hmdp.document.ShopDocument;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopSearchService;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 使用 Elasticsearch 完成商铺全文检索，并维护 MySQL 到 ES 的文档映射。
 */
@Service
public class ShopSearchServiceImpl implements IShopSearchService {

    @Resource
    private ElasticsearchOperations elasticsearchOperations;

    @Resource
    private ShopMapper shopMapper;

    /**
     * bool 查询组合全文条件和类型过滤，评分作为主排序字段。
     */
    @Override
    public Result search(String keyword, Long typeId, Integer current, Integer size) {
        int page = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1
                ? SystemConstants.DEFAULT_PAGE_SIZE
                : Math.min(size, SystemConstants.MAX_PAGE_SIZE);
        String normalizedKeyword = keyword == null ? null : keyword.trim();

        ensureIndex();
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.bool(bool -> {
                    if (StringUtils.hasText(normalizedKeyword)) {
                        // 名称权重更高，简介补充召回与名称不完全相同的商铺。
                        bool.must(must -> must.multiMatch(match -> match
                                .fields("name^2", "description")
                                .query(normalizedKeyword)));
                    }
                    if (typeId != null) {
                        bool.filter(filter -> filter.term(term -> term
                                .field("typeId")
                                .value(typeId)));
                    }
                    return bool;
                }))
                .withPageable(PageRequest.of(
                        page - 1,
                        pageSize,
                        Sort.by(Sort.Order.desc("score"))))
                .build();

        SearchHits<ShopDocument> hits = elasticsearchOperations.search(query, ShopDocument.class);
        List<ShopDocument> shops = hits.getSearchHits().stream()
                .map(SearchHit::getContent)
                .toList();
        return Result.ok(shops, hits.getTotalHits());
    }

    /**
     * 消费 MQ 时回查数据库；记录不存在则删除 ES 残留文档。
     */
    @Override
    public void syncShop(Long shopId) {
        ensureIndex();
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            elasticsearchOperations.delete(String.valueOf(shopId), ShopDocument.class);
            return;
        }
        elasticsearchOperations.save(ShopDocument.from(shop));
    }

    /**
     * 首次启动仅在索引不存在时导入，避免每次重启都造成索引短暂不可用。
     */
    @Override
    public synchronized long initializeIfAbsent() {
        return ensureIndex();
    }

    /**
     * 管理员重建时先删除旧索引，再按注解映射创建并全量导入。
     */
    @Override
    public synchronized long rebuild() {
        IndexOperations indexOperations = elasticsearchOperations.indexOps(ShopDocument.class);
        if (indexOperations.exists()) {
            indexOperations.delete();
        }
        if (!indexOperations.createWithMapping()) {
            throw new IllegalStateException("Failed to rebuild shop search index");
        }
        return indexAllShops();
    }

    private synchronized long ensureIndex() {
        IndexOperations indexOperations = elasticsearchOperations.indexOps(ShopDocument.class);
        if (indexOperations.exists()) {
            return 0L;
        }
        if (!indexOperations.createWithMapping()) {
            throw new IllegalStateException("Failed to create shop search index");
        }
        return indexAllShops();
    }

    private long indexAllShops() {
        List<ShopDocument> documents = shopMapper.selectList(null).stream()
                .map(ShopDocument::from)
                .toList();
        if (!documents.isEmpty()) {
            elasticsearchOperations.save(documents);
        }
        return documents.size();
    }
}
