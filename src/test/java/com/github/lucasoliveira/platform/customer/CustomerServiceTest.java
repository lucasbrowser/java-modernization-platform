package com.github.lucasoliveira.platform.customer;

import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.dto.CustomerRequest;
import com.github.lucasoliveira.platform.customer.dto.CustomerResponse;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import com.github.lucasoliveira.platform.customer.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository repo;

    @InjectMocks
    private CustomerService service;

    @Test
    void shouldCreateCustomer() {

        CustomerRequest request = new CustomerRequest(
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        Customer customer = new Customer();

        customer.setName(request.name());
        customer.setEmail(request.email());
        customer.setDocument(request.document());
        customer.setPhone(request.phone());

        when(repo.existsByEmailIgnoreCase(request.email()))
                .thenReturn(false);

        when(repo.existsByDocument(request.document()))
                .thenReturn(false);

        when(repo.save(any(Customer.class)))
                .thenReturn(customer);

        CustomerResponse response = service.create(request);

        assertThat(response).isNotNull();
        assertThat(response.name()).isEqualTo("Lucas");
        assertThat(response.email()).isEqualTo("lucas@example.com");
        assertThat(response.document()).isEqualTo("12345678900");

        verify(repo).save(any(Customer.class));
    }

    @Test
    void shouldRejectDuplicatedEmail() {

        CustomerRequest request = new CustomerRequest(
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        when(repo.existsByEmailIgnoreCase(request.email()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer email already registered");

        verify(repo, never()).save(any(Customer.class));
    }

    @Test
    void shouldRejectDuplicatedDocument() {

        CustomerRequest request = new CustomerRequest(
                "Lucas",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        when(repo.existsByEmailIgnoreCase(request.email()))
                .thenReturn(false);

        when(repo.existsByDocument(request.document()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer document already registered");

        verify(repo, never()).save(any(Customer.class));
    }

    @Test
    void shouldFindAllCustomersWithPagination() {

        Customer customer = new Customer();

        customer.setName("Lucas");
        customer.setEmail("lucas@example.com");
        customer.setDocument("12345678900");
        customer.setPhone("65999999999");

        Pageable pageable = PageRequest.of(0, 10);

        Page<Customer> page =
                new PageImpl<>(List.of(customer), pageable, 1);

        when(repo.findAll(pageable))
                .thenReturn(page);

        Page<CustomerResponse> result =
                service.findAll(pageable);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);

        assertThat(result.getContent().get(0).name())
                .isEqualTo("Lucas");

        assertThat(result.getContent().get(0).email())
                .isEqualTo("lucas@example.com");

        assertThat(result.getTotalElements())
                .isEqualTo(1);

        assertThat(result.getTotalPages())
                .isEqualTo(1);

        verify(repo).findAll(pageable);
    }

    @Test
    void shouldFindCustomerById() {

        UUID id = UUID.randomUUID();

        Customer customer = new Customer();

        customer.setName("Lucas");
        customer.setEmail("lucas@example.com");
        customer.setDocument("12345678900");
        customer.setPhone("65999999999");

        when(repo.findById(id))
                .thenReturn(Optional.of(customer));

        CustomerResponse response =
                service.findById(id);

        assertThat(response).isNotNull();
        assertThat(response.name())
                .isEqualTo("Lucas");

        verify(repo).findById(id);
    }

    @Test
    void shouldThrowWhenCustomerDoesNotExist() {

        UUID id = UUID.randomUUID();

        when(repo.findById(id))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Customer not found: " + id);

        verify(repo).findById(id);
    }
}