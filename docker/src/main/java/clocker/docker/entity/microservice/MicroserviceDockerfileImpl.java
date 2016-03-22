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
package clocker.docker.entity.microservice;

import java.util.Collection;

import clocker.docker.entity.DockerInfrastructure;
import clocker.docker.entity.container.application.VanillaDockerApplication;
import clocker.docker.location.DockerLocation;

import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.core.entity.AbstractApplication;
import org.apache.brooklyn.core.entity.Entities;

public class MicroserviceDockerfileImpl extends AbstractApplication implements MicroserviceDockerfile {

    protected VanillaDockerApplication vanillaDockerApplication = null;

    @Override
    public void initApp() {
        vanillaDockerApplication = addChild(EntitySpec.create(VanillaDockerApplication.class)
                .configure("containerName", config().get(CONTAINER_NAME))
                .configure("dockerfileUrl", config().get(DOCKERFILE_URL))
                .configure("openPorts", config().get(OPEN_PORTS))
                .configure("directPorts", config().get(DIRECT_PORTS))
                .configure("portBindings", config().get(PORT_BINDINGS))
                .configure("env", config().get(DOCKER_CONTAINER_ENVIRONMENT))
                .configure("volumeMappings", config().get(DOCKER_HOST_VOLUME_MAPPING)));
    }

    @Override
    protected void doStart(Collection<? extends Location> locations) {
        Optional<Location> dockerLocation = Iterables.tryFind(getLocations(), Predicates.instanceOf(DockerLocation.class));

        if (!dockerLocation.isPresent()) {
            String locationName = DOCKER_LOCATION_PREFIX + getId();
            DockerInfrastructure dockerInfrastructure = addChild(EntitySpec.create(DockerInfrastructure.class)
                    .configure(DockerInfrastructure.DOCKER_HOST_CLUSTER_MIN_SIZE, 1)
                    .configure(DockerInfrastructure.SDN_ENABLE, false)
                    .configure(DockerInfrastructure.LOCATION_NAME, locationName)
                    .displayName("Docker Infrastructure"));

            Entities.start(dockerInfrastructure, locations);

            dockerLocation = Optional.of(getManagementContext().getLocationRegistry().resolve(locationName));
        }

        Entities.start(vanillaDockerApplication, dockerLocation.asSet());
    }

}
