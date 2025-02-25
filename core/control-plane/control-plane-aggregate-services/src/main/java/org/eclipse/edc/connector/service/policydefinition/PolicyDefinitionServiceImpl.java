/*
 *  Copyright (c) 2022 ZF Friedrichshafen AG
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Contributors:
 *       ZF Friedrichshafen AG - Initial API and Implementation
 *
 */

package org.eclipse.edc.connector.service.policydefinition;

import org.eclipse.edc.connector.contract.spi.offer.store.ContractDefinitionStore;
import org.eclipse.edc.connector.policy.spi.PolicyDefinition;
import org.eclipse.edc.connector.policy.spi.observe.PolicyDefinitionObservable;
import org.eclipse.edc.connector.policy.spi.store.PolicyDefinitionStore;
import org.eclipse.edc.connector.service.query.QueryValidator;
import org.eclipse.edc.connector.spi.policydefinition.PolicyDefinitionService;
import org.eclipse.edc.policy.model.AndConstraint;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.Constraint;
import org.eclipse.edc.policy.model.Expression;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.MultiplicityConstraint;
import org.eclipse.edc.policy.model.OrConstraint;
import org.eclipse.edc.policy.model.XoneConstraint;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.transaction.spi.TransactionContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.lang.String.format;
import static org.eclipse.edc.spi.query.Criterion.criterion;

public class PolicyDefinitionServiceImpl implements PolicyDefinitionService {

    private final TransactionContext transactionContext;
    private final PolicyDefinitionStore policyStore;
    private final ContractDefinitionStore contractDefinitionStore;
    private final PolicyDefinitionObservable observable;
    private final QueryValidator queryValidator;

    public PolicyDefinitionServiceImpl(TransactionContext transactionContext, PolicyDefinitionStore policyStore,
                                       ContractDefinitionStore contractDefinitionStore, PolicyDefinitionObservable observable) {
        this.transactionContext = transactionContext;
        this.policyStore = policyStore;
        this.contractDefinitionStore = contractDefinitionStore;
        this.observable = observable;
        queryValidator = new QueryValidator(PolicyDefinition.class, getSubtypeMap());
    }

    @Override
    public PolicyDefinition findById(String policyId) {
        return transactionContext.execute(() ->
                policyStore.findById(policyId));
    }

    @Override
    public ServiceResult<Stream<PolicyDefinition>> query(QuerySpec query) {
        var result = queryValidator.validate(query);

        if (result.failed()) {
            return ServiceResult.badRequest(format("Error validating schema: %s", result.getFailureDetail()));
        }
        return ServiceResult.success(transactionContext.execute(() -> policyStore.findAll(query)));
    }


    @Override
    public @NotNull ServiceResult<PolicyDefinition> deleteById(String policyId) {
        return transactionContext.execute(() -> {

            var contractFilter = criterion("contractPolicyId", "=", policyId);
            var accessFilter = criterion("accessPolicyId", "=", policyId);

            var queryContractPolicyFilter = QuerySpec.Builder.newInstance().filter(contractFilter).build();
            try (var contractDefinitionOnPolicy = contractDefinitionStore.findAll(queryContractPolicyFilter)) {
                if (contractDefinitionOnPolicy.findAny().isPresent()) {
                    return ServiceResult.conflict(format("PolicyDefinition %s cannot be deleted as it is referenced by at least one contract definition", policyId));
                }
            }

            var queryAccessPolicyFilter = QuerySpec.Builder.newInstance().filter(accessFilter).build();
            try (var accessDefinitionOnPolicy = contractDefinitionStore.findAll(queryAccessPolicyFilter)) {
                if (accessDefinitionOnPolicy.findAny().isPresent()) {
                    return ServiceResult.conflict(format("PolicyDefinition %s cannot be deleted as it is referenced by at least one contract definition", policyId));
                }
            }

            var deleted = policyStore.delete(policyId);
            deleted.onSuccess(pd -> observable.invokeForEach(l -> l.deleted(pd)));
            return ServiceResult.from(deleted);
        });
    }

    @Override
    public @NotNull ServiceResult<PolicyDefinition> create(PolicyDefinition policyDefinition) {
        return transactionContext.execute(() -> {
            var saveResult = policyStore.create(policyDefinition);
            saveResult.onSuccess(v -> observable.invokeForEach(l -> l.created(policyDefinition)));
            return ServiceResult.from(saveResult);
        });
    }


    @Override
    public ServiceResult<PolicyDefinition> update(PolicyDefinition policyDefinition) {
        return transactionContext.execute(() -> {
            var updateResult = policyStore.update(policyDefinition);
            updateResult.onSuccess(p -> observable.invokeForEach(l -> l.updated(p)));
            return ServiceResult.from(updateResult);
        });
    }

    private Map<Class<?>, List<Class<?>>> getSubtypeMap() {
        return Map.of(
                Constraint.class, List.of(MultiplicityConstraint.class, AtomicConstraint.class),
                MultiplicityConstraint.class, List.of(AndConstraint.class, OrConstraint.class, XoneConstraint.class),
                Expression.class, List.of(LiteralExpression.class)
        );
    }
}
