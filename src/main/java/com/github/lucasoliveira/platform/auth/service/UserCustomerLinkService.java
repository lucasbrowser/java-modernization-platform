package com.github.lucasoliveira.platform.auth.service;

import com.github.lucasoliveira.platform.auth.entity.Role;
import com.github.lucasoliveira.platform.auth.entity.User;
import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAction;
import com.github.lucasoliveira.platform.auth.entity.UserCustomerLinkAudit;
import com.github.lucasoliveira.platform.auth.repository.UserCustomerLinkAuditRepository;
import com.github.lucasoliveira.platform.auth.repository.UserRepository;
import com.github.lucasoliveira.platform.common.exception.ResourceNotFoundException;
import com.github.lucasoliveira.platform.customer.repository.CustomerRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserCustomerLinkService {

    private static final int MAX_JUSTIFICATION_LENGTH = 500;

    private final AuthenticatedUserResolver authenticatedUserResolver;
    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final UserCustomerLinkAuditRepository auditRepository;

    public UserCustomerLinkService(
            AuthenticatedUserResolver authenticatedUserResolver,
            UserRepository userRepository,
            CustomerRepository customerRepository,
            UserCustomerLinkAuditRepository auditRepository
    ) {
        this.authenticatedUserResolver = authenticatedUserResolver;
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public UserCustomerLinkResult assignCustomer(
            UUID targetUserId,
            UUID customerId,
            String justification
    ) {
        UUID actorUserId = requirePersistedAdmin();
        String normalizedJustification = normalizeJustification(justification);
        if (customerId == null) {
            throw new IllegalArgumentException("Customer id is required");
        }
        User target = findTargetForUpdate(targetUserId);

        if (customerId.equals(target.getCustomerId())) {
            return new UserCustomerLinkResult(targetUserId, target.getCustomerId(), false);
        }

        if (!customerRepository.existsById(customerId)) {
            throw new ResourceNotFoundException("Customer not found: " + customerId);
        }

        UUID previousCustomerId = target.getCustomerId();
        UserCustomerLinkAction action = previousCustomerId == null
                ? UserCustomerLinkAction.CREATE
                : UserCustomerLinkAction.CHANGE;

        target.setCustomerId(customerId);
        userRepository.save(target);
        auditRepository.saveAndFlush(new UserCustomerLinkAudit(
                actorUserId,
                targetUserId,
                previousCustomerId,
                customerId,
                action,
                normalizedJustification
        ));

        return new UserCustomerLinkResult(targetUserId, customerId, true);
    }

    @Transactional
    public UserCustomerLinkResult removeCustomer(UUID targetUserId, String justification) {
        UUID actorUserId = requirePersistedAdmin();
        String normalizedJustification = normalizeJustification(justification);
        User target = findTargetForUpdate(targetUserId);
        UUID previousCustomerId = target.getCustomerId();

        if (previousCustomerId == null) {
            return new UserCustomerLinkResult(targetUserId, null, false);
        }

        target.setCustomerId(null);
        userRepository.save(target);
        auditRepository.saveAndFlush(new UserCustomerLinkAudit(
                actorUserId,
                targetUserId,
                previousCustomerId,
                null,
                UserCustomerLinkAction.REMOVE,
                normalizedJustification
        ));

        return new UserCustomerLinkResult(targetUserId, null, true);
    }

    private UUID requirePersistedAdmin() {
        UUID actorUserId = authenticatedUserResolver.resolveCurrent().userId();
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
        if (actor.getRole() != Role.ADMIN) {
            throw new AccessDeniedException("Persisted user does not have ADMIN role");
        }
        return actorUserId;
    }

    private User findTargetForUpdate(UUID targetUserId) {
        User target = userRepository.findByIdForUpdate(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));
        if (target.getRole() != Role.USER) {
            throw new IllegalArgumentException("Target user must have USER role");
        }
        return target;
    }

    private String normalizeJustification(String justification) {
        if (justification == null) {
            throw new IllegalArgumentException("Justification is required");
        }
        String normalized = justification.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Justification must not be blank");
        }
        if (normalized.length() > MAX_JUSTIFICATION_LENGTH) {
            throw new IllegalArgumentException("Justification must not exceed 500 characters");
        }
        return normalized;
    }
}
