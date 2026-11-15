package com.example.springbootpostgresapi.controller;

import com.example.springbootpostgresapi.dto.CreateProductRequest;
import com.example.springbootpostgresapi.dto.UpdateProductRequest;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.service.ProductService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/products")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public List<Product> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasAnyRole('USER','ADMIN')")
    public Product getProductById(@PathVariable Long id) {
        return productService.getProductById(id);
    }

    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Product> createProduct(@Valid @RequestBody CreateProductRequest request, Authentication authentication) {
        Product createdProduct = productService.createProduct(request, authentication.getName());
        return ResponseEntity
                .created(URI.create("/api/products/" + createdProduct.getId()))
                .body(createdProduct);
    }

    @PutMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Product updateProduct(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request, Authentication authentication) {
        return productService.updateProduct(id, request, authentication.getName());
    }

    @DeleteMapping("/products/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id, Authentication authentication) {
        productService.deleteProduct(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
