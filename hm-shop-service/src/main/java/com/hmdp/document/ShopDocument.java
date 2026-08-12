package com.hmdp.document;

import com.hmdp.entity.Shop;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;


/**
 * 商铺搜索文档，名称和简介使用 IK 细粒度建索引、粗粒度执行查询。
 */
@Data
@NoArgsConstructor
@Document(indexName = ShopDocument.INDEX_NAME, createIndex = false)
public class ShopDocument {

    public static final String INDEX_NAME = "shop";

    @Id
    private Long id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String name;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    @Field(type = FieldType.Long)
    private Long typeId;

    private String images;
    private String area;
    private String address;
    private Double x;
    private Double y;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;

    @Field(type = FieldType.Integer)
    private Integer score;

    private String openHours;

    /**
     * MQ 消费端始终用数据库完整记录构造文档，保证重复消息可幂等覆盖。
     */
    public static ShopDocument from(Shop shop) {
        ShopDocument document = new ShopDocument();
        document.setId(shop.getId());
        document.setName(shop.getName());
        document.setDescription(shop.getDescription());
        document.setTypeId(shop.getTypeId());
        document.setImages(shop.getImages());
        document.setArea(shop.getArea());
        document.setAddress(shop.getAddress());
        document.setX(shop.getX());
        document.setY(shop.getY());
        document.setAvgPrice(shop.getAvgPrice());
        document.setSold(shop.getSold());
        document.setComments(shop.getComments());
        document.setScore(shop.getScore());
        document.setOpenHours(shop.getOpenHours());
        return document;
    }
}
