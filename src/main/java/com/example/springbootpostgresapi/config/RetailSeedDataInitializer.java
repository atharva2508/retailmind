package com.example.springbootpostgresapi.config;

import com.example.springbootpostgresapi.entity.OrderLine;
import com.example.springbootpostgresapi.entity.Product;
import com.example.springbootpostgresapi.entity.ReturnLine;
import com.example.springbootpostgresapi.entity.StockSnapshot;
import com.example.springbootpostgresapi.entity.Store;
import com.example.springbootpostgresapi.entity.User;
import com.example.springbootpostgresapi.repository.OrderLineRepository;
import com.example.springbootpostgresapi.repository.ProductRepository;
import com.example.springbootpostgresapi.repository.ReturnLineRepository;
import com.example.springbootpostgresapi.repository.StockSnapshotRepository;
import com.example.springbootpostgresapi.repository.StoreRepository;
import com.example.springbootpostgresapi.repository.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@RequiredArgsConstructor
public class RetailSeedDataInitializer {

    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final StockSnapshotRepository stockSnapshotRepository;
    private final OrderLineRepository orderLineRepository;
    private final ReturnLineRepository returnLineRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.admin-password:}")
    private String adminPassword;
    @Value("${app.seed.manager-password:}")
    private String managerPassword;
    @Value("${app.seed.reviewer-password:}")
    private String reviewerPassword;
    @Value("${app.seed.user-password:}")
    private String userPassword;

    @Bean
    @ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
    public CommandLineRunner seedRetailData() {
        return args -> {
            if (adminPassword.isBlank() || managerPassword.isBlank() || reviewerPassword.isBlank() || userPassword.isBlank()) {
                throw new IllegalStateException("Seeding is enabled but a seed password is not set (SEED_*_PASSWORD)");
            }
            if (storeRepository.count() == 0) {
                storeRepository.saveAll(List.of(
                        Store.builder().storeCode("MAIN").name("Main Store").active(true).timezone("Asia/Kolkata").build(),
                        Store.builder().storeCode("NORTH").name("North Store").active(true).timezone("Asia/Kolkata").build(),
                        Store.builder().storeCode("SOUTH").name("South Store").active(true).timezone("Asia/Kolkata").build()
                ));
            }

            if (productRepository.count() == 0) {
                productRepository.saveAll(List.of(
                        Product.builder().sku("SKU-1001").name("Wireless Mouse").description("Ergonomic mouse").price(new BigDecimal("29.99")).stock(30).isEssential(true).defaultReorderThreshold(8).build(),
                        Product.builder().sku("SKU-2002").name("USB-C Cable").description("High-speed cable").price(new BigDecimal("12.50")).stock(18).isEssential(true).defaultReorderThreshold(6).build(),
                        Product.builder().sku("SKU-3003").name("Mechanical Keyboard").description("Compact keyboard").price(new BigDecimal("79.00")).stock(14).isEssential(false).defaultReorderThreshold(0).build()
                ));
            }

            Store mainStore = storeRepository.findByStoreCode("MAIN").orElseThrow();
            Product mouse = productRepository.findBySku("SKU-1001").orElseThrow();
            Product cable = productRepository.findBySku("SKU-2002").orElseThrow();

            if (stockSnapshotRepository.count() == 0) {
                stockSnapshotRepository.saveAll(List.of(
                        StockSnapshot.builder().store(mainStore).product(mouse).businessDate(LocalDate.of(2026, 11, 4)).snapshotType("EOD").asOf(LocalDateTime.of(2026, 11, 4, 18, 0)).bal(12).aud(13).rac(1).held(4).absent(false).build(),
                        StockSnapshot.builder().store(mainStore).product(mouse).businessDate(LocalDate.of(2026, 11, 5)).snapshotType("SOD").asOf(LocalDateTime.of(2026, 11, 5, 8, 0)).bal(18).aud(19).rac(1).held(4).absent(false).build(),
                        StockSnapshot.builder().store(mainStore).product(mouse).businessDate(LocalDate.of(2026, 11, 5)).snapshotType("EOD").asOf(LocalDateTime.of(2026, 11, 5, 18, 0)).bal(13).aud(14).rac(1).held(3).absent(false).build(),
                        StockSnapshot.builder().store(mainStore).product(cable).businessDate(LocalDate.of(2026, 11, 4)).snapshotType("EOD").asOf(LocalDateTime.of(2026, 11, 4, 18, 0)).bal(9).aud(9).rac(0).held(2).absent(false).build(),
                        StockSnapshot.builder().store(mainStore).product(cable).businessDate(LocalDate.of(2026, 11, 5)).snapshotType("SOD").asOf(LocalDateTime.of(2026, 11, 5, 8, 0)).bal(8).aud(8).rac(0).held(2).absent(false).build(),
                        StockSnapshot.builder().store(mainStore).product(cable).businessDate(LocalDate.of(2026, 11, 5)).snapshotType("EOD").asOf(LocalDateTime.of(2026, 11, 5, 18, 0)).bal(7).aud(7).rac(0).held(1).absent(false).build()
                ));
            }

            if (orderLineRepository.count() == 0) {
                orderLineRepository.saveAll(List.of(
                        OrderLine.builder().orderId("ORD-1001").lineNo(1).store(mainStore).product(mouse).qtyOrdered(4).qtyConfirmed(2).qtyCancelled(0).qtyShipped(1).status("PARTIALLY_CONFIRMED").cancelReason(null).fulfilledFrom("STORE").createdAt(LocalDateTime.of(2026, 11, 5, 9, 10)).confirmedAt(LocalDateTime.of(2026, 11, 5, 9, 12)).cancelledAt(null).shippedAt(null).build(),
                        OrderLine.builder().orderId("ORD-1002").lineNo(1).store(mainStore).product(cable).qtyOrdered(3).qtyConfirmed(0).qtyCancelled(0).qtyShipped(0).status("NOT_FOUND").cancelReason("NOT_FOUND").fulfilledFrom("STORE").createdAt(LocalDateTime.of(2026, 11, 5, 10, 0)).confirmedAt(null).cancelledAt(LocalDateTime.of(2026, 11, 5, 10, 20)).shippedAt(null).build(),
                        OrderLine.builder().orderId("ORD-1003").lineNo(1).store(mainStore).product(mouse).qtyOrdered(2).qtyConfirmed(2).qtyCancelled(1).qtyShipped(1).status("CONFIRMED").cancelReason("CUSTOMER").fulfilledFrom("STORE").createdAt(LocalDateTime.of(2026, 11, 5, 11, 0)).confirmedAt(LocalDateTime.of(2026, 11, 5, 11, 5)).cancelledAt(LocalDateTime.of(2026, 11, 5, 11, 30)).shippedAt(null).build()
                ));
            }

            if (returnLineRepository.count() == 0) {
                returnLineRepository.saveAll(List.of(
                        ReturnLine.builder().returnId("RET-9001").lineNo(1).store(mainStore).product(mouse).qty(2).returnCode("03").receivedAt(LocalDateTime.of(2026, 11, 1, 9, 0)).movedToBalAt(null).sentToVendorAt(null).sourceOrderId("ORD-1004").build(),
                        ReturnLine.builder().returnId("RET-9002").lineNo(1).store(mainStore).product(cable).qty(1).returnCode("09").receivedAt(LocalDateTime.of(2026, 11, 3, 9, 0)).movedToBalAt(null).sentToVendorAt(LocalDateTime.of(2026, 11, 5, 15, 30)).sourceOrderId("ORD-1005").build()
                ));
            }

            if (userRepository.findByUsername("admin").isEmpty()) {
                userRepository.save(User.builder()
                        .username("admin")
                        .email("admin@retailmind.local")
                        .password(passwordEncoder.encode(adminPassword))
                        .role("ROLE_ADMIN")
                        .storeId(1L)
                        .build());
            }

            if (userRepository.findByUsername("store_manager").isEmpty()) {
                userRepository.save(User.builder()
                        .username("store_manager")
                        .email("manager@retailmind.local")
                        .password(passwordEncoder.encode(managerPassword))
                        .role("ROLE_STORE_MANAGER")
                        .storeId(1L)
                        .build());
            }

            if (userRepository.findByUsername("reviewer").isEmpty()) {
                userRepository.save(User.builder()
                        .username("reviewer")
                        .email("reviewer@retailmind.local")
                        .password(passwordEncoder.encode(reviewerPassword))
                        .role("ROLE_REVIEWER")
                        .storeId(1L)
                        .build());
            }

            if (userRepository.findByUsername("user").isEmpty()) {
                userRepository.save(User.builder()
                        .username("user")
                        .email("user@retailmind.local")
                        .password(passwordEncoder.encode(userPassword))
                        .role("ROLE_USER")
                        .storeId(1L)
                        .build());
            }
        };
    }
}
