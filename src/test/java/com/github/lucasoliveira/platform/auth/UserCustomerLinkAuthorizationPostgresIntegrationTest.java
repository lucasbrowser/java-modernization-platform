package com.github.lucasoliveira.platform.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.common.security.JwtService;
import com.github.lucasoliveira.platform.customer.entity.Customer;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserCustomerLinkAuthorizationPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;

    @Test
    void anonymousRequestsToBothOperationsReturn401WithoutDatabaseChanges() throws Exception {
        Fixture fixture = fixture("anonymous");

        mockMvc.perform(put("/admin/users/{id}/customer-link", fixture.target().getId())
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody(fixture.firstCustomer().getId())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/users/{id}/customer-link/removal", fixture.target().getId())
                        .contentType(MediaType.APPLICATION_JSON).content(removalBody()))
                .andExpect(status().isUnauthorized());

        assertUnchanged(fixture.target().getId(), null, 0);
    }

    @Test
    void persistedUserRequestsToBothOperationsReturn403WithoutDatabaseChanges() throws Exception {
        Fixture fixture = fixture("user");
        String token = jwtService.generateToken(fixture.actor().getEmail(), "USER");

        mockMvc.perform(put("/admin/users/{id}/customer-link", fixture.target().getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(assignBody(fixture.firstCustomer().getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/users/{id}/customer-link/removal", fixture.target().getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(removalBody()))
                .andExpect(status().isForbidden());

        assertUnchanged(fixture.target().getId(), null, 0);
    }

    @Test
    void persistedAdminCanAssignChangeAndRemoveAndIsRecordedAsAuditActor() throws Exception {
        Fixture fixture = fixture("admin");
        String token = jwtService.generateToken(fixture.actor().getEmail(), "ADMIN");

        mockMvc.perform(put("/admin/users/{id}/customer-link", fixture.target().getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(fixture.firstCustomer().getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        assertEquals(fixture.firstCustomer().getId(), currentCustomerId(fixture.target().getId()));

        mockMvc.perform(put("/admin/users/{id}/customer-link", fixture.target().getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(fixture.secondCustomer().getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        assertEquals(fixture.secondCustomer().getId(), currentCustomerId(fixture.target().getId()));

        mockMvc.perform(post("/admin/users/{id}/customer-link/removal", fixture.target().getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(removalBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        assertNull(currentCustomerId(fixture.target().getId()));

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT actor_user_id, target_user_id, previous_customer_id, new_customer_id, action " +
                        "FROM user_customer_link_audit WHERE target_user_id = ? ORDER BY occurred_at, id",
                fixture.target().getId());
        assertEquals(3, events.size());
        assertAuditEvent(events.get(0), fixture, null, fixture.firstCustomer().getId(), "CREATE");
        assertAuditEvent(events.get(1), fixture, fixture.firstCustomer().getId(), fixture.secondCustomer().getId(), "CHANGE");
        assertAuditEvent(events.get(2), fixture, fixture.secondCustomer().getId(), null, "REMOVE");

        User adminTarget = saveUser("admin-target-" + UUID.randomUUID(), Role.ADMIN, null);
        mockMvc.perform(put("/admin/users/{id}/customer-link", adminTarget.getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(fixture.firstCustomer().getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
        assertNull(currentCustomerId(adminTarget.getId()));
        assertEquals(0, auditCount(adminTarget.getId()));
    }

    @Test
    void staleAdminJwtCannotOperateWhenPersistedRoleIsUser() throws Exception {
        Fixture fixture = fixture("stale-admin");
        String adminToken = jwtService.generateToken(fixture.actor().getEmail(), "ADMIN");
        User actor = userRepository.findById(fixture.actor().getId()).orElseThrow();
        actor.setRole(Role.USER);
        userRepository.saveAndFlush(actor);

        mockMvc.perform(put("/admin/users/{id}/customer-link", fixture.target().getId())
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(fixture.firstCustomer().getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/users/{id}/customer-link/removal", fixture.target().getId())
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(removalBody()))
                .andExpect(status().isForbidden());

        assertUnchanged(fixture.target().getId(), null, 0);
    }

    @Test
    void validJwtWithUnknownSubjectReturnsObservedGlobal500WithoutDatabaseChanges() throws Exception {
        Fixture fixture = fixture("unknown-subject");
        String token = jwtService.generateToken("missing-" + UUID.randomUUID() + "@example.com", "ADMIN");

        mockMvc.perform(put("/admin/users/{id}/customer-link", fixture.target().getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(assignBody(fixture.firstCustomer().getId())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));
        mockMvc.perform(post("/admin/users/{id}/customer-link/removal", fixture.target().getId())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(removalBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."));

        assertUnchanged(fixture.target().getId(), null, 0);
    }

    private Fixture fixture(String label) {
        String suffix = label + "-" + UUID.randomUUID();
        Customer first = saveCustomer(suffix + "-first");
        Customer second = saveCustomer(suffix + "-second");
        User actor = saveUser(suffix + "-actor", label.equals("user") ? Role.USER : Role.ADMIN, null);
        User target = saveUser(suffix + "-target", Role.USER, null);
        return new Fixture(actor, target, first, second);
    }

    private Customer saveCustomer(String suffix) {
        Customer customer = new Customer();
        customer.setName("Customer " + suffix);
        customer.setEmail(suffix + "@example.com");
        customer.setDocument("DOC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return customerRepository.saveAndFlush(customer);
    }

    private User saveUser(String suffix, Role role, UUID customerId) {
        User user = new User();
        user.setName("User " + suffix);
        user.setEmail(suffix + "@example.com");
        user.setPassword("encoded-password");
        user.setRole(role);
        user.setCustomerId(customerId);
        return userRepository.saveAndFlush(user);
    }

    private void assertUnchanged(UUID targetId, UUID expectedCustomerId, int expectedAuditCount) {
        assertEquals(expectedCustomerId, currentCustomerId(targetId));
        assertEquals(expectedAuditCount, auditCount(targetId));
    }

    private UUID currentCustomerId(UUID targetId) {
        return jdbcTemplate.queryForObject("SELECT customer_id FROM users WHERE id = ?", UUID.class, targetId);
    }

    private int auditCount(UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_customer_link_audit WHERE target_user_id = ?", Integer.class, targetId);
    }

    private void assertAuditEvent(
            Map<String, Object> event,
            Fixture fixture,
            UUID previous,
            UUID next,
            String action
    ) {
        assertEquals(fixture.actor().getId(), event.get("actor_user_id"));
        assertEquals(fixture.target().getId(), event.get("target_user_id"));
        assertEquals(previous, event.get("previous_customer_id"));
        assertEquals(next, event.get("new_customer_id"));
        assertEquals(action, event.get("action"));
    }

    private String assignBody(UUID customerId) throws Exception {
        return objectMapper.writeValueAsString(Map.of("customerId", customerId, "justification", "Approved test change"));
    }

    private String removalBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of("justification", "Approved test removal"));
    }

    private record Fixture(User actor, User target, Customer firstCustomer, Customer secondCustomer) {
    }
}
