package com.onlineshopping.document;


import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;

@Document(indexName = "products")
@Data
public class ProductDocument {
    @Id
    Long id;
    String name;
    String description;
    Long price;
    String categoryName;
    String seller;
}
