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
package clocker.docker.location.strategy;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import clocker.docker.location.DockerHostLocation;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.util.collections.MutableList;

/**
 * A basic placement strategy for Docker containers, implemented as a {@link Predicate} and a {@link Comparator}.
 */
public abstract class BasicDockerPlacementStrategy extends AbstractDockerPlacementStrategy
        implements Predicate<DockerHostLocation>, Comparator<DockerHostLocation> {

    protected Entity entity;

    @Override
    public List<DockerHostLocation> filterLocations(List<DockerHostLocation> locations, Entity context) {
        if (locations == null || locations.isEmpty()) {
            return ImmutableList.of();
        }

        entity = context;
        List<DockerHostLocation> available = MutableList.copyOf(locations);
        Collections.sort(available, this);
        return ImmutableList.copyOf(Iterables.filter(available, this));
    }

    /**
     * A {@link Predicate} function that selects Docker hosts that satisy the strategy requirements.
     */
    @Override
    public boolean apply(DockerHostLocation input) {
        return true;
    }

    /**
     * A {@link Comparator} function that orders Docker hosts according to suitability, best first.
     * <p>
     * Defaults to no ordering.
     */
    @Override
    public int compare(DockerHostLocation l1, DockerHostLocation l2) {
        return Ordering.allEqual().compare(l1, l2);
    }

}
