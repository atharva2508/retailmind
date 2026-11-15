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

    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    @Transactional
    public Product createProduct(CreateProductRequest request) {
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

        return productRepository.save(product);
    }

    @Transactional
    public Product updateProduct(Long id, UpdateProductRequest request) {
        Product existingProduct = getProductById(id);

        if (!existingProduct.getSku().equals(request.sku())
                && productRepository.findBySku(request.sku()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU already exists");
        }

        existingProduct.setSku(request.sku());
        existingProduct.setName(request.name());
        existingProduct.setDescription(request.description());
        existingProduct.setPrice(request.price());
        existingProduct.setStock(request.stock());
        existingProduct.setIsEssential(existingProduct.getIsEssential() != null ? existingProduct.getIsEssential() : false);
        existingProduct.setDefaultReorderThreshold(existingProduct.getDefaultReorderThreshold() != null ? existingProduct.getDefaultReorderThreshold() : 0);

        return productRepository.save(existingProduct);
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = getProductById(id);
        productRepository.delete(product);
    }
}
