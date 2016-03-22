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
package clocker.docker.test;

import static org.testng.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.testng.annotations.Test;

import clocker.docker.entity.DockerInfrastructure;
import clocker.docker.location.DockerLocation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.location.LocationDefinition;

/**
 * Brooklyn managed basic Docker infrastructure.
 */
public class DockerInfrastructureLiveTest extends AbstractClockerIntegrationTest {

    @Test(groups="Live")
    public void testRegistersLocation() throws Exception {
        app.createAndManageChild(EntitySpec.create(DockerInfrastructure.class)
                .configure(DockerInfrastructure.DOCKER_HOST_CLUSTER_MIN_SIZE, 1)
                .configure(DockerInfrastructure.LOCATION_NAME_PREFIX, "dynamicdockertest")
                .configure(DockerInfrastructure.SDN_ENABLE, false)
                .displayName("Docker Infrastructure"));
        app.start(ImmutableList.of(testLocation));

        LocationDefinition infraLocDef = findLocationMatchingName("dynamicdockertest.*");
        Location infraLoc = mgmt.getLocationRegistry().resolve(infraLocDef);
        assertTrue(infraLoc instanceof DockerLocation, "loc="+infraLoc);
    }

    @Test(groups="Live")
    public void testDeploysTrivialApplication() throws Exception {
        DockerInfrastructureTests.testDeploysTrivialApplication(app, testLocation);
    }

    private LocationDefinition findLocationMatchingName(String regex) {
        List<String> contenders = Lists.newArrayList();
        for (Map.Entry<String, LocationDefinition> entry : mgmt.getLocationRegistry().getDefinedLocations().entrySet()) {
            String name = entry.getValue().getName();
            if (name.matches(regex)) {
                return entry.getValue();
            }
            contenders.add(name);
        }
        throw new NoSuchElementException("No location matching regex: "+regex+"; contenders were "+contenders);
    }
}
