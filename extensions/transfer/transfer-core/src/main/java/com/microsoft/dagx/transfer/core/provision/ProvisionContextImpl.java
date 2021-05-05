/*
 * Copyright (c) Microsoft Corporation.
 * All rights reserved.
 */

package com.microsoft.dagx.transfer.core.provision;

import com.microsoft.dagx.spi.transfer.provision.ProvisionContext;
import com.microsoft.dagx.spi.transfer.store.TransferProcessStore;
import com.microsoft.dagx.spi.types.domain.transfer.DestinationSecretToken;
import com.microsoft.dagx.spi.types.domain.transfer.ProvisionedDataDestinationResource;
import com.microsoft.dagx.spi.types.domain.transfer.ProvisionedResource;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Provision context backed by a {@link TransferProcessStore}.
 */
public class ProvisionContextImpl implements ProvisionContext {
    private TransferProcessStore processStore;
    private final Consumer<ProvisionedResource> resourceCallback;
    private final BiConsumer<ProvisionedDataDestinationResource, DestinationSecretToken> destinationCallback;

    public ProvisionContextImpl(TransferProcessStore processStore,
                                Consumer<ProvisionedResource> resourceCallback,
                                BiConsumer<ProvisionedDataDestinationResource, DestinationSecretToken> destinationCallback) {
        this.processStore = processStore;
        this.resourceCallback = resourceCallback;
        this.destinationCallback = destinationCallback;
    }

    @Override
    public void callback(ProvisionedResource resource) {
        resourceCallback.accept(resource);
    }

    @Override
    public void callback(ProvisionedDataDestinationResource resource, DestinationSecretToken secretToken) {
        destinationCallback.accept(resource, secretToken);
    }

    @Override
    public void create(String processId, String resourceDefinitionId, Object data) {
        processStore.createData(processId, resourceDefinitionId, data);
    }

    @Override
    public void update(String processId, String resourceDefinitionId, Object data) {
        processStore.updateData(processId, resourceDefinitionId, data);
    }

    @Override
    public <T> @Nullable T find(Class<T> type, String processId, String resourceDefinitionId) {
        return processStore.findData(type, processId, resourceDefinitionId);
    }
}
