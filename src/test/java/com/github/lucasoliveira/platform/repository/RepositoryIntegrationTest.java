package com.github.lucasoliveira.platform.repository;

import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAction;
import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAudit;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.repository.UserCustomerLinkAuditRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private UserCustomerLinkAuditRepository userCustomerLinkAuditRepository;

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
    void shouldAllowMultipleUsersToShareOneCustomer() {
        Customer customer = new Customer();
        customer.setName("Cliente compartilhado");
        customer.setEmail("shared.customer@example.com");
        customer.setDocument("SHARED-CUSTOMER-001");
        Customer savedCustomer = customerRepository.saveAndFlush(customer);

        User first = newUser("first.shared@example.com");
        first.setCustomerId(savedCustomer.getId());
        User second = newUser("second.shared@example.com");
        second.setCustomerId(savedCustomer.getId());

        userRepository.saveAllAndFlush(List.of(first, second));

        assertThat(userRepository.findAll())
                .filteredOn(user -> savedCustomer.getId().equals(user.getCustomerId()))
                .hasSize(2);
    }

    @Test
    void shouldRejectReferenceToMissingCustomer() {
        User user = newUser("missing.customer@example.com");
        user.setCustomerId(UUID.randomUUID());

        assertThatThrownBy(() -> userRepository.saveAndFlush(user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepUserUnlinkedWhenEmailMatchesCustomer() {
        Customer customer = new Customer();
        customer.setName("Email coincidente");
        customer.setEmail("same.email@example.com");
        customer.setDocument("SAME-EMAIL-001");
        customerRepository.saveAndFlush(customer);

        User user = newUser("same.email@example.com");
        User savedUser = userRepository.saveAndFlush(user);

        assertThat(savedUser.getCustomerId()).isNull();
    }

    @Test
    void shouldPersistUserCustomerLinkAuditFields() {
        User actor = userRepository.saveAndFlush(newUser("audit.actor@example.com"));
        User target = userRepository.saveAndFlush(newUser("audit.target@example.com"));
        UUID previousCustomerId = UUID.randomUUID();
        UUID newCustomerId = UUID.randomUUID();

        UserCustomerLinkAudit saved = userCustomerLinkAuditRepository.saveAndFlush(
                new UserCustomerLinkAudit(
                        actor.getId(),
                        target.getId(),
                        previousCustomerId,
                        newCustomerId,
                        UserCustomerLinkAction.CHANGE,
                        "Correção autorizada"
                )
        );

        UserCustomerLinkAudit found = userCustomerLinkAuditRepository
                .findById(saved.getId())
                .orElseThrow();

        assertThat(found.getActorUserId()).isEqualTo(actor.getId());
        assertThat(found.getTargetUserId()).isEqualTo(target.getId());
        assertThat(found.getPreviousCustomerId()).isEqualTo(previousCustomerId);
        assertThat(found.getNewCustomerId()).isEqualTo(newCustomerId);
        assertThat(found.getAction()).isEqualTo(UserCustomerLinkAction.CHANGE);
        assertThat(found.getOccurredAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(found.getJustification()).isEqualTo("Correção autorizada");
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

    @Test
    void shouldPaginateAndSortCustomersByName() {
        Customer lucas = new Customer();
        lucas.setName("Lucas");
        lucas.setEmail("lucas.integration@example.com");
        lucas.setDocument("11111111111");

        Customer ana = new Customer();
        ana.setName("Ana");
        ana.setEmail("ana.integration@example.com");
        ana.setDocument("22222222222");

        Customer bruno = new Customer();
        bruno.setName("Bruno");
        bruno.setEmail("bruno.integration@example.com");
        bruno.setDocument("33333333333");

        Customer carlos = new Customer();
        carlos.setName("Carlos");
        carlos.setEmail("carlos.integration@example.com");
        carlos.setDocument("44444444444");

        Customer maria = new Customer();
        maria.setName("Maria");
        maria.setEmail("maria.integration@example.com");
        maria.setDocument("55555555555");

        customerRepository.saveAll(List.of(
                lucas,
                ana,
                bruno,
                carlos,
                maria
        ));

        Pageable pageable = PageRequest.of(
                0,
                2,
                Sort.by(Sort.Direction.ASC, "name")
        );

        Page<Customer> result = customerRepository.findAll(pageable);

        assertThat(result.getContent())
                .extracting(Customer::getName)
                .containsExactly("Ana", "Bruno");

        assertThat(result.getTotalElements())
                .isEqualTo(5);

        assertThat(result.getTotalPages())
                .isEqualTo(3);

        assertThat(result.getNumber())
                .isEqualTo(0);

        assertThat(result.getSize())
                .isEqualTo(2);
    }

    @Test
    void shouldReturnSecondPageOfCustomers() {
        Customer lucas = new Customer();
        lucas.setName("Lucas");
        lucas.setEmail("lucas.page2@example.com");
        lucas.setDocument("66666666666");

        Customer ana = new Customer();
        ana.setName("Ana");
        ana.setEmail("ana.page2@example.com");
        ana.setDocument("77777777777");

        Customer bruno = new Customer();
        bruno.setName("Bruno");
        bruno.setEmail("bruno.page2@example.com");
        bruno.setDocument("88888888888");

        Customer carlos = new Customer();
        carlos.setName("Carlos");
        carlos.setEmail("carlos.page2@example.com");
        carlos.setDocument("99999999999");

        customerRepository.saveAll(List.of(
                lucas,
                ana,
                bruno,
                carlos
        ));

        Pageable pageable = PageRequest.of(
                1,
                2,
                Sort.by(Sort.Direction.ASC, "name")
        );

        Page<Customer> result = customerRepository.findAll(pageable);

        assertThat(result.getContent())
                .extracting(Customer::getName)
                .containsExactly("Carlos", "Lucas");

        assertThat(result.getNumber())
                .isEqualTo(1);

        assertThat(result.getSize())
                .isEqualTo(2);

        assertThat(result.getTotalElements())
                .isEqualTo(4);

        assertThat(result.getTotalPages())
                .isEqualTo(2);
    }

    private User newUser(String email) {
        User user = new User();
        user.setName("Usuário de teste");
        user.setEmail(email);
        user.setPassword("encoded-password");
        user.setRole(Role.USER);
        return user;
    }
}
