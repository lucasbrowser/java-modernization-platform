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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    CustomerRepository repository;

    @InjectMocks
    CustomerService service;

    @Test
    void shouldCreateCustomer() {
        CustomerRequest request = new CustomerRequest(
                "Lucas Oliveira",
                "LUCAS@EXAMPLE.COM",
                "12345678900",
                "65999999999"
        );

        when(repository.existsByEmailIgnoreCase(request.email()))
                .thenReturn(false);

        when(repository.existsByDocument(request.document()))
                .thenReturn(false);

        when(repository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerResponse response = service.create(request);

        assertEquals("Lucas Oliveira", response.name());
        assertEquals("lucas@example.com", response.email());
        assertEquals("12345678900", response.document());
        assertEquals("65999999999", response.phone());

        verify(repository).save(any(Customer.class));
    }

    @Test
    void shouldRejectDuplicatedEmail() {
        CustomerRequest request = new CustomerRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        when(repository.existsByEmailIgnoreCase(request.email()))
                .thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(request)
        );

        assertEquals(
                "Customer email already registered",
                exception.getMessage()
        );

        verify(repository, never()).save(any(Customer.class));
        verify(repository, never()).existsByDocument(anyString());
    }

    @Test
    void shouldRejectDuplicatedDocument() {
        CustomerRequest request = new CustomerRequest(
                "Lucas Oliveira",
                "lucas@example.com",
                "12345678900",
                "65999999999"
        );

        when(repository.existsByEmailIgnoreCase(request.email()))
                .thenReturn(false);

        when(repository.existsByDocument(request.document()))
                .thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.create(request)
        );

        assertEquals(
                "Customer document already registered",
                exception.getMessage()
        );

        verify(repository, never()).save(any(Customer.class));
    }

    @Test
    void shouldFindAllCustomers() {
        Customer first = new Customer();
        first.setName("Cliente 1");
        first.setEmail("cliente1@example.com");
        first.setDocument("11111111111");

        Customer second = new Customer();
        second.setName("Cliente 2");
        second.setEmail("cliente2@example.com");
        second.setDocument("22222222222");

        when(repository.findAll())
                .thenReturn(List.of(first, second));

        List<CustomerResponse> response = service.findAll();

        assertEquals(2, response.size());
        assertEquals("Cliente 1", response.get(0).name());
        assertEquals("Cliente 2", response.get(1).name());

        verify(repository).findAll();
    }

    @Test
    void shouldFindCustomerById() {
        UUID id = UUID.randomUUID();

        Customer customer = new Customer();
        customer.setName("Cliente Teste");
        customer.setEmail("cliente@example.com");
        customer.setDocument("12345678900");

        when(repository.findById(id))
                .thenReturn(Optional.of(customer));

        CustomerResponse response = service.findById(id);

        assertEquals("Cliente Teste", response.name());
        assertEquals("cliente@example.com", response.email());

        verify(repository).findById(id);
    }

    @Test
    void shouldThrowExceptionWhenCustomerDoesNotExist() {
        UUID id = UUID.randomUUID();

        when(repository.findById(id))
                .thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class,
                () -> service.findById(id)
        );

        assertEquals(
                "Customer not found: " + id,
                exception.getMessage()
        );
    }
}