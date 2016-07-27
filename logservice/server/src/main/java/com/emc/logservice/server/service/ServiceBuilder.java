/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.emc.logservice.server.service;

import com.emc.logservice.contracts.StreamSegmentStore;
import com.emc.logservice.server.ReadIndexFactory;
import com.emc.logservice.server.MetadataRepository;
import com.emc.logservice.server.OperationLogFactory;
import com.emc.logservice.server.SegmentContainerFactory;
import com.emc.logservice.server.SegmentContainerManager;
import com.emc.logservice.server.SegmentContainerRegistry;
import com.emc.logservice.server.SegmentToContainerMapper;
import com.emc.logservice.server.containers.StreamSegmentContainerFactory;
import com.emc.logservice.server.logs.DurableLogConfig;
import com.emc.logservice.server.logs.DurableLogFactory;
import com.emc.logservice.server.reading.ContainerReadIndexFactory;
import com.emc.logservice.storageabstraction.DurableDataLogFactory;
import com.emc.logservice.storageabstraction.StorageFactory;
import com.google.common.base.Preconditions;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Helps create StreamSegmentStore Instances.
 */
public abstract class ServiceBuilder implements AutoCloseable {
    //region Members

    protected final SegmentToContainerMapper segmentToContainerMapper;
    protected final ServiceBuilderConfig serviceBuilderConfig;
    private final ExecutorService executorService;
    private OperationLogFactory operationLogFactory;
    private ReadIndexFactory readIndexFactory;
    private DurableDataLogFactory dataLogFactory;
    private StorageFactory storageFactory;
    private SegmentContainerFactory containerFactory;
    private SegmentContainerRegistry containerRegistry;
    private SegmentContainerManager containerManager;
    private MetadataRepository metadataRepository;

    //endregion

    //region Constructor

    public ServiceBuilder(ServiceBuilderConfig serviceBuilderConfig) {
        Preconditions.checkNotNull(serviceBuilderConfig, "config");
        this.serviceBuilderConfig = serviceBuilderConfig;
        ServiceConfig serviceConfig = this.serviceBuilderConfig.getServiceConfig();
        this.segmentToContainerMapper = new SegmentToContainerMapper(serviceConfig.getContainerCount());
        this.executorService = Executors.newScheduledThreadPool(serviceConfig.getThreadPoolSize());
    }

    //endregion

    //region AutoCloseable Implementation

    @Override
    public void close() {
        if (this.containerManager != null) {
            this.containerManager.close();
            this.containerManager = null;
        }

        if (this.containerRegistry != null) {
            this.containerRegistry.close();
            this.containerRegistry = null;
        }

        if (this.dataLogFactory != null) {
            this.dataLogFactory.close();
            this.dataLogFactory = null;
        }

        if (this.storageFactory != null) {
            this.storageFactory.close();
            this.storageFactory = null;
        }

        this.executorService.shutdown();
    }

    //endregion

    //region Service Builder

    /**
     * Creates a new instance of StreamSegmentStore using the components generated by this class.
     *
     * @return
     */
    public StreamSegmentStore createStreamSegmentService() {
        return new StreamSegmentService(getSegmentContainerRegistry(), this.segmentToContainerMapper);
    }

    /**
     * Creates or gets the instance of SegmentContainerManager used throughout this ServiceBuilder.
     *
     * @return
     */
    public SegmentContainerManager getContainerManager() {
        return getSingleton(this.containerManager, this::createSegmentContainerManager, cr -> this.containerManager = cr);
    }

    /**
     * Creates or gets the instance of the SegmentContainerRegistry used throughout this ServiceBuilder.
     * @return
     */
    public SegmentContainerRegistry getSegmentContainerRegistry() {
        return getSingleton(this.containerRegistry, this::createSegmentContainerRegistry, cr -> this.containerRegistry = cr);
    }

    //endregion

    //region Component Builders

    protected abstract DurableDataLogFactory createDataLogFactory();

    protected abstract StorageFactory createStorageFactory();

    protected abstract MetadataRepository createMetadataRepository();

    protected abstract SegmentContainerManager createSegmentContainerManager();

    protected ReadIndexFactory createReadIndexFactory() {
        return new ContainerReadIndexFactory();
    }

    private SegmentContainerFactory createSegmentContainerFactory() {
        MetadataRepository metadataRepository = getSingleton(this.metadataRepository, this::createMetadataRepository, mr -> this.metadataRepository = mr);
        ReadIndexFactory readIndexFactory = getSingleton(this.readIndexFactory, this::createReadIndexFactory, cf -> this.readIndexFactory = cf);
        StorageFactory storageFactory = getSingleton(this.storageFactory, this::createStorageFactory, sf -> this.storageFactory = sf);
        OperationLogFactory operationLogFactory = getSingleton(this.operationLogFactory, this::createOperationLogFactory, olf -> this.operationLogFactory = olf);
        return new StreamSegmentContainerFactory(metadataRepository, operationLogFactory, readIndexFactory, storageFactory, this.executorService);
    }

    private SegmentContainerRegistry createSegmentContainerRegistry() {
        SegmentContainerFactory containerFactory = getSingleton(this.containerFactory, this::createSegmentContainerFactory, scf -> this.containerFactory = scf);
        return new StreamSegmentContainerRegistry(containerFactory, this.executorService);
    }

    private OperationLogFactory createOperationLogFactory() {
        DurableDataLogFactory dataLogFactory = getSingleton(this.dataLogFactory, this::createDataLogFactory, dlf -> this.dataLogFactory = dlf);
        DurableLogConfig durableLogConfig = this.serviceBuilderConfig.getDurableLogConfig();
        return new DurableLogFactory(durableLogConfig, dataLogFactory, this.executorService);
    }

    private <T> T getSingleton(T instance, Supplier<T> creator, Consumer<T> setter) {
        if (instance != null) {
            return instance;
        }

        instance = creator.get();
        setter.accept(instance);
        return instance;
    }

    //endregion
}
