/*
 * Copyright 2014-2016 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package clocker.mesos.location;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import clocker.mesos.entity.MesosAttributes;
import clocker.mesos.entity.MesosCluster;
import clocker.mesos.location.framework.MesosFrameworkLocation;

import com.google.common.base.Function;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Predicates;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.LocationDefinition;
import org.apache.brooklyn.api.location.MachineLocation;
import org.apache.brooklyn.api.location.MachineProvisioningLocation;
import org.apache.brooklyn.api.location.NoMachinesAvailableException;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.location.AbstractLocation;
import org.apache.brooklyn.core.location.BasicLocationDefinition;
import org.apache.brooklyn.core.location.LocationConfigKeys;
import org.apache.brooklyn.core.location.dynamic.DynamicLocation;
import org.apache.brooklyn.core.location.dynamic.LocationOwner;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

public class MesosLocation extends AbstractLocation implements MachineProvisioningLocation<MachineLocation>, DynamicLocation<MesosCluster, MesosLocation>, Closeable {

    public static final ConfigKey<Entity> MESOS_CLUSTER = MesosAttributes.MESOS_CLUSTER.getConfigKey();
    public static final String PREFIX = "mesos-";

    private static final Logger LOG = LoggerFactory.getLogger(MesosLocation.class);

    public static final ConfigKey<String> LOCATION_NAME = ConfigKeys.newStringConfigKey("locationName");

    @SetFromFlag("locationRegistrationId")
    private String locationRegistrationId;

    private transient MesosCluster cluster;

    @SetFromFlag("tasks")
    private final SetMultimap<MachineProvisioningLocation, String> tasks = Multimaps.synchronizedSetMultimap(HashMultimap.<MachineProvisioningLocation, String>create());

    public MesosLocation() {
        this(Maps.newLinkedHashMap());
    }

    public MesosLocation(Map properties) {
        super(properties);

        if (isLegacyConstruction()) {
            init();
        }
    }

    @Override
    public void init() {
        super.init();
        cluster = (MesosCluster) checkNotNull(getConfig(OWNER), "owner");
    }
    
    @Override
    public void rebind() {
        super.rebind();
        
        cluster = (MesosCluster) getConfig(OWNER);
        
        if (getConfig(LOCATION_NAME) != null) {
            register();
        }
    }

    @Override
    public LocationDefinition register() {
        String locationName = checkNotNull(getConfig(LOCATION_NAME), "config %s", LOCATION_NAME.getName());

        LocationDefinition check = getManagementContext().getLocationRegistry().getDefinedLocationByName(locationName);
        if (check != null) {
            throw new IllegalStateException("Location " + locationName + " is already defined: " + check);
        }

        String locationSpec = String.format(MesosResolver.MESOS_CLUSTER_SPEC, getId()) + String.format(":(name=\"%s\")", locationName);

        LocationDefinition definition = new BasicLocationDefinition(locationName, locationSpec, ImmutableMap.<String, Object>of());
        getManagementContext().getLocationRegistry().updateDefinedLocation(definition);
        
        locationRegistrationId = definition.getId();
        requestPersist();
        
        return definition;
    }
    
    @Override
    public void deregister() {
        if (locationRegistrationId != null) {
            getManagementContext().getLocationRegistry().removeDefinedLocation(locationRegistrationId);
            locationRegistrationId = null;
            requestPersist();
        }
    }
    
    public MesosCluster getMesosCluster() { return cluster; }

    @Override
    public MesosCluster getOwner() {
        return cluster;
    }

    @Override
    public void close() throws IOException {
        LOG.info("Close called on Mesos cluster: {}", this);
    }

    @Override
    public ToStringHelper string() {
        return super.string()
                .omitNullValues()
                .add("cluster", cluster);
    }

    @Override
    public MachineLocation obtain(Map<?, ?> flags) throws NoMachinesAvailableException {
        // Check context for entity being deployed
        Object context = flags.get(LocationConfigKeys.CALLER_CONTEXT.getName());
        if (context != null && !(context instanceof Entity)) {
            throw new IllegalStateException("Invalid location context: " + context);
        }
        Entity entity = (Entity) context;

        // Get the available hosts based on placement strategies
        Collection<Entity> frameworks = cluster.sensors().get(MesosCluster.MESOS_FRAMEWORKS).getMembers();
        Iterable<MesosFrameworkLocation> locations = Iterables.transform(Iterables.filter(frameworks, LocationOwner.class),
                new Function<LocationOwner, MesosFrameworkLocation>() {
                    @Override
                    public MesosFrameworkLocation apply(LocationOwner input) {
                        return (MesosFrameworkLocation) input.getDynamicLocation();
                    }});
        for (MesosFrameworkLocation framework : locations) {
            if (framework instanceof MachineProvisioningLocation && framework.isSupported(entity)) {
                MachineLocation task = ((MachineProvisioningLocation) framework).obtain(flags);
                tasks.put((MachineProvisioningLocation) framework, task.getId());
                return task;
            }
        }

        // No suitable framework found
        throw new NoMachinesAvailableException("No framework found to start entity: " + entity);
    }

    @Override
    public MachineProvisioningLocation<MachineLocation> newSubLocation(Map<?, ?> newFlags) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void release(MachineLocation task) {
        String id = task.getId();
        Set<MachineProvisioningLocation> set = Multimaps.filterValues(tasks, Predicates.equalTo(id)).keySet();
        if (set.isEmpty()) {
            throw new IllegalArgumentException("Request to release "+task+", but not currently allocated");
        }
        MachineProvisioningLocation framework = Iterables.getOnlyElement(set);
        LOG.debug("Request to remove task mapping {} to {}", framework, id);
        framework.release(task);
        tasks.remove(framework, id);
    }

    @Override
    public Map<String, Object> getProvisioningFlags(Collection<String> tags) {
        return Maps.newLinkedHashMap();
    }

}
