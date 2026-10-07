package com.github.lucasoliveira.platform.repository;

import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.order.entity.Order;
import com.github.lucasoliveira.platform.order.entity.OrderStatus;
import com.github.lucasoliveira.platform.order.repository.OrderRepository;
import com.github.lucasoliveira.platform.product.entity.Product;
import com.github.lucasoliveira.platform.product.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldFindUserByEmailIgnoringCase() {
        User user = new User();
        user.setName("Lucas Oliveira");
        user.setEmail("lucas@example.com");
        user.setPassword("encoded-password");
        user.setRole(Role.USER);

        userRepository.saveAndFlush(user);

        assertThat(userRepository.findByEmailIgnoreCase("LUCAS@EXAMPLE.COM"))
                .isPresent()
                .get()
                .extracting(User::getEmail)
                .isEqualTo("lucas@example.com");
    }

    @Test
    void shouldCheckCustomerEmailAndDocumentExistence() {
        Customer customer = new Customer();
        customer.setName("Cliente Teste");
        customer.setEmail("cliente@example.com");
        customer.setDocument("12345678900");
        customer.setPhone("65999999999");

        customerRepository.saveAndFlush(customer);

        assertThat(customerRepository.existsByEmailIgnoreCase("CLIENTE@EXAMPLE.COM"))
                .isTrue();

        assertThat(customerRepository.existsByDocument("12345678900"))
                .isTrue();

        assertThat(customerRepository.existsByEmailIgnoreCase("outro@example.com"))
                .isFalse();
    }

    @Test
    void shouldFindProductBySku() {
        Product product = new Product();
        product.setSku("SKU-001");
        product.setName("Notebook Enterprise");
        product.setDescription("Produto para teste de repository");
        product.setPrice(new BigDecimal("3499.90"));
        product.setStock(10);
        product.setActive(true);

        productRepository.saveAndFlush(product);

        assertThat(productRepository.findBySku("SKU-001"))
                .isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.getName())
                            .isEqualTo("Notebook Enterprise");

                    assertThat(found.getPrice())
                            .isEqualByComparingTo("3499.90");

                    assertThat(found.getStock())
                            .isEqualTo(10);
                });

        assertThat(productRepository.existsBySku("SKU-001"))
                .isTrue();
    }

    @Test
    void shouldFindOrdersByCustomerId() {
        Customer customer = new Customer();
        customer.setName("Cliente Pedido");
        customer.setEmail("pedido@example.com");
        customer.setDocument("98765432100");

        Customer savedCustomer = customerRepository.saveAndFlush(customer);

        Order order = new Order();
        order.setCustomer(savedCustomer);
        order.setStatus(OrderStatus.CREATED);
        order.setTotalAmount(new BigDecimal("499.90"));

        orderRepository.saveAndFlush(order);

        List<Order> orders =
                orderRepository.findByCustomerId(savedCustomer.getId());

        assertThat(orders)
                .hasSize(1)
                .first()
                .satisfies(found -> {
                    assertThat(found.getCustomer().getId())
                            .isEqualTo(savedCustomer.getId());

                    assertThat(found.getStatus())
                            .isEqualTo(OrderStatus.CREATED);

                    assertThat(found.getTotalAmount())
                            .isEqualByComparingTo("499.90");
                });
    }
}