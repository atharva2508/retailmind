package com.example.springbootpostgresapi.service;

import com.example.springbootpostgresapi.dto.CreateProductRequest;
import com.example.springbootpostgresapi.dto.UpdateProductRequest;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.repository.ProductRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final AuditEventService auditEventService;

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    @Transactional
    public Product createProduct(CreateProductRequest request, String actor) {
        if (productRepository.findBySku(request.sku()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU already exists");
        }

        Product product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .description(request.description())
                .price(request.price())
                .stock(request.stock())
                .isEssential(false)
                .defaultReorderThreshold(0)
                .build();

        Product saved = productRepository.save(product);
        auditEventService.log("CREATE", "PRODUCT", saved.getId(), null, actor, "Created SKU " + saved.getSku() + " at price " + saved.getPrice());
        return saved;
    }

    @Transactional
    public Product updateProduct(Long id, UpdateProductRequest request, String actor) {
        Product existingProduct = getProductById(id);

        // Price changes need review: create a pricing proposal instead.
        if (existingProduct.getPrice().compareTo(request.price()) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price cannot be changed here, create a pricing proposal");
        }

        if (!existingProduct.getSku().equals(request.sku())
                && productRepository.findBySku(request.sku()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU already exists");
        }

        existingProduct.setSku(request.sku());
        existingProduct.setName(request.name());
        existingProduct.setDescription(request.description());
        existingProduct.setStock(request.stock());
        existingProduct.setIsEssential(existingProduct.getIsEssential() != null ? existingProduct.getIsEssential() : false);
        existingProduct.setDefaultReorderThreshold(existingProduct.getDefaultReorderThreshold() != null ? existingProduct.getDefaultReorderThreshold() : 0);

        Product saved = productRepository.save(existingProduct);
        auditEventService.log("UPDATE", "PRODUCT", saved.getId(), null, actor, "Updated SKU " + saved.getSku());
        return saved;
    }

    @Transactional
    public void deleteProduct(Long id, String actor) {
        Product product = getProductById(id);
        productRepository.delete(product);
        auditEventService.log("DELETE", "PRODUCT", id, null, actor, "Deleted SKU " + product.getSku());
    }
}
