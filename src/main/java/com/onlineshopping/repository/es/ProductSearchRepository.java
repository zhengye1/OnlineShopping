package com.onlineshopping.repository.es;

import com.onlineshopping.document.ProductDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ProductSearchRepository extends ElasticsearchRepository<ProductDocument, Long> {
    List<ProductDocument> findByNameContainingOrDescriptionContaining(String name, String description);
}
