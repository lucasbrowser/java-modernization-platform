package com.github.lucasoliveira.platform.auth;

import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAction;
import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAudit;
import com.github.lucasoliveira.platform.auth.repository.UserCustomerLinkAuditRepository;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserIdentity;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserNotFoundException;
import com.github.lucasoliveira.platform.auth.service.AuthenticatedUserResolver;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkResult;
import com.github.lucasoliveira.platform.auth.service.UserCustomerLinkService;
import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCustomerLinkServiceTest {

    private static final String JUSTIFICATION = "  Approved by support  ";

    @Mock
    private AuthenticatedUserResolver authenticatedUserResolver;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private UserCustomerLinkAuditRepository auditRepository;

    private UserCustomerLinkService service;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        service = new UserCustomerLinkService(
                authenticatedUserResolver,
                userRepository,
                customerRepository,
                auditRepository
        );
        actorId = UUID.randomUUID();
        when(authenticatedUserResolver.resolveCurrent())
                .thenReturn(new AuthenticatedUserIdentity(actorId, null));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, Role.ADMIN, null)));
    }

    @Test
    void shouldCreateAssociationAndRecordCreateAudit() {
        UUID targetId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        User target = user(targetId, Role.USER, null);
        when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));
        when(customerRepository.existsById(customerId)).thenReturn(true);

        UserCustomerLinkResult result = service.assignCustomer(targetId, customerId, JUSTIFICATION);

        assertThat(result).isEqualTo(new UserCustomerLinkResult(targetId, customerId, true));
        assertThat(target.getCustomerId()).isEqualTo(customerId);
        verify(userRepository).findByIdForUpdate(targetId);
        verify(userRepository).save(target);
        UserCustomerLinkAudit audit = capturedAudit();
        assertThat(audit.getActorUserId()).isEqualTo(actorId);
        assertThat(audit.getTargetUserId()).isEqualTo(targetId);
        assertThat(audit.getPreviousCustomerId()).isNull();
        assertThat(audit.getNewCustomerId()).isEqualTo(customerId);
        assertThat(audit.getAction()).isEqualTo(UserCustomerLinkAction.CREATE);
        assertThat(audit.getJustification()).isEqualTo("Approved by support");
    }

    @Test
    void shouldChangeAssociationAndRecordPreviousAndNewCustomer() {
        UUID targetId = UUID.randomUUID();
        UUID previousCustomerId = UUID.randomUUID();
        UUID newCustomerId = UUID.randomUUID();
        User target = user(targetId, Role.USER, previousCustomerId);
        when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));
        when(customerRepository.existsById(newCustomerId)).thenReturn(true);

        UserCustomerLinkResult result = service.assignCustomer(targetId, newCustomerId, "change approved");

        assertThat(result.changed()).isTrue();
        assertThat(target.getCustomerId()).isEqualTo(newCustomerId);
        UserCustomerLinkAudit audit = capturedAudit();
        assertThat(audit.getAction()).isEqualTo(UserCustomerLinkAction.CHANGE);
        assertThat(audit.getPreviousCustomerId()).isEqualTo(previousCustomerId);
        assertThat(audit.getNewCustomerId()).isEqualTo(newCustomerId);
    }

    @Test
    void shouldRemoveAssociationAndRecordRemoveAudit() {
        UUID targetId = UUID.randomUUID();
        UUID previousCustomerId = UUID.randomUUID();
        User target = user(targetId, Role.USER, previousCustomerId);
        when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));

        UserCustomerLinkResult result = service.removeCustomer(targetId, JUSTIFICATION);

        assertThat(result).isEqualTo(new UserCustomerLinkResult(targetId, null, true));
        assertThat(target.getCustomerId()).isNull();
        verify(userRepository).save(target);
        UserCustomerLinkAudit audit = capturedAudit();
        assertThat(audit.getAction()).isEqualTo(UserCustomerLinkAction.REMOVE);
        assertThat(audit.getPreviousCustomerId()).isEqualTo(previousCustomerId);
        assertThat(audit.getNewCustomerId()).isNull();
    }

    @Test
    void shouldNotWriteOrAuditWhenAssociationAlreadyExists() {
        UUID targetId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(targetId))
                .thenReturn(Optional.of(user(targetId, Role.USER, customerId)));

        UserCustomerLinkResult result = service.assignCustomer(targetId, customerId, JUSTIFICATION);

        assertThat(result).isEqualTo(new UserCustomerLinkResult(targetId, customerId, false));
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldNotWriteOrAuditWhenRemovingMissingAssociation() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(targetId))
                .thenReturn(Optional.of(user(targetId, Role.USER, null)));

        UserCustomerLinkResult result = service.removeCustomer(targetId, JUSTIFICATION);

        assertThat(result).isEqualTo(new UserCustomerLinkResult(targetId, null, false));
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldRejectActorWhosePersistedRoleIsNotAdmin() {
        when(userRepository.findById(actorId)).thenReturn(Optional.of(user(actorId, Role.USER, null)));

        assertThatThrownBy(() -> service.removeCustomer(UUID.randomUUID(), JUSTIFICATION))
                .isInstanceOf(AccessDeniedException.class);

        verify(userRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldRejectActorWithoutPersistedUser() {
        when(userRepository.findById(actorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeCustomer(UUID.randomUUID(), JUSTIFICATION))
                .isInstanceOf(AuthenticatedUserNotFoundException.class);

        verify(userRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldRejectTargetThatIsNotUser() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(targetId))
                .thenReturn(Optional.of(user(targetId, Role.ADMIN, null)));

        assertThatThrownBy(() -> service.removeCustomer(targetId, JUSTIFICATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Target user must have USER role");

        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldReportMissingTarget() {
        UUID targetId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.removeCustomer(targetId, JUSTIFICATION))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found: " + targetId);

        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldReportMissingCustomerWithoutChangingTargetOrWritingAudit() {
        UUID targetId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        User target = user(targetId, Role.USER, null);
        when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));
        when(customerRepository.existsById(customerId)).thenReturn(false);

        assertThatThrownBy(() -> service.assignCustomer(targetId, customerId, JUSTIFICATION))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Customer not found: " + customerId);

        assertThat(target.getCustomerId()).isNull();
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(auditRepository);
    }

    @Test
    void shouldRejectNullBlankAndTooLongNormalizedJustification() {
        UUID targetId = UUID.randomUUID();
        assertInvalidJustification(targetId, null, "Justification is required");
        assertInvalidJustification(targetId, " \t\n ", "Justification must not be blank");
        assertInvalidJustification(targetId, "x".repeat(501), "Justification must not exceed 500 characters");

        verify(userRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(customerRepository, auditRepository);
    }

    @Test
    void shouldAcceptExactlyFiveHundredCharactersAfterStripping() {
        UUID targetId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        User target = user(targetId, Role.USER, null);
        when(userRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));
        when(customerRepository.existsById(customerId)).thenReturn(true);
        String justification = "  " + "x".repeat(500) + "  ";

        service.assignCustomer(targetId, customerId, justification);

        assertThat(capturedAudit().getJustification()).isEqualTo("x".repeat(500));
    }

    @Test
    void shouldRejectNullCustomerIdAsInvalidInput() {
        assertThatThrownBy(() -> service.assignCustomer(UUID.randomUUID(), null, JUSTIFICATION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Customer id is required");
        verify(userRepository, never()).findByIdForUpdate(any());
        verifyNoInteractions(customerRepository, auditRepository);
    }

    private void assertInvalidJustification(UUID targetId, String justification, String message) {
        assertThatThrownBy(() -> service.removeCustomer(targetId, justification))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(message);
    }

    private UserCustomerLinkAudit capturedAudit() {
        ArgumentCaptor<UserCustomerLinkAudit> captor = ArgumentCaptor.forClass(UserCustomerLinkAudit.class);
        verify(auditRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private User user(UUID id, Role role, UUID customerId) {
        User user = new User();
        user.setRole(role);
        user.setCustomerId(customerId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
