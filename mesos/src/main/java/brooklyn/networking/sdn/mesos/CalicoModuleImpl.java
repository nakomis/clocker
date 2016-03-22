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
package brooklyn.networking.sdn.mesos;

import static org.apache.brooklyn.util.ssh.BashCommands.sudo;

import java.net.InetAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import clocker.docker.networking.entity.VirtualNetwork;
import clocker.docker.networking.entity.sdn.SdnProvider;
import clocker.docker.networking.entity.sdn.util.SdnUtils;
import clocker.docker.networking.location.NetworkProvisioningExtension;

import com.google.common.base.CharMatcher;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.entity.EntitySpec;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.TaskWrapper;
import org.apache.brooklyn.core.config.render.RendererHints;
import org.apache.brooklyn.core.effector.ssh.SshEffectorTasks;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.feed.ConfigToAttributes;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.location.Machines;
import org.apache.brooklyn.entity.group.BasicGroup;
import org.apache.brooklyn.entity.group.DynamicGroup;
import org.apache.brooklyn.entity.stock.BasicStartableImpl;
import org.apache.brooklyn.entity.stock.DelegateEntity;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.QuorumCheck.QuorumChecks;
import org.apache.brooklyn.util.core.internal.ssh.SshTool;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.guava.Maybe;
import org.apache.brooklyn.util.net.Cidr;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.StringPredicates;
import org.apache.brooklyn.util.text.Strings;

import brooklyn.entity.mesos.MesosCluster;
import brooklyn.entity.mesos.MesosSlave;
import brooklyn.entity.mesos.MesosUtils;
import brooklyn.entity.mesos.task.marathon.MarathonTask;

public class CalicoModuleImpl extends BasicStartableImpl implements CalicoModule {

    private static final Logger LOG = LoggerFactory.getLogger(CalicoModule.class);

    /** Held while obtaining new IP addresses for containers. */
    protected transient final Object addressMutex = new Object[0];

    /** Mutex for provisioning new networks */
    protected transient final Object networkMutex = new Object[0];

    @Override
    public void init() {
        LOG.info("Starting SDN provider id {}", getId());
        super.init();

        BasicGroup networks = addChild(EntitySpec.create(BasicGroup.class)
                .configure(BasicGroup.RUNNING_QUORUM_CHECK, QuorumChecks.atLeastOneUnlessEmpty())
                .configure(BasicGroup.UP_QUORUM_CHECK, QuorumChecks.atLeastOneUnlessEmpty())
                .displayName("SDN Managed Networks"));

        BasicGroup applications = addChild(EntitySpec.create(BasicGroup.class)
                .configure(BasicGroup.RUNNING_QUORUM_CHECK, QuorumChecks.atLeastOneUnlessEmpty())
                .configure(BasicGroup.UP_QUORUM_CHECK, QuorumChecks.atLeastOneUnlessEmpty())
                .displayName("SDN Networked Applications"));

        sensors().set(SDN_NETWORKS, networks);
        sensors().set(SDN_APPLICATIONS, applications);

        synchronized (addressMutex) {
            sensors().set(SUBNET_ADDRESS_ALLOCATIONS, Maps.<String, Integer>newConcurrentMap());
        }

        synchronized (networkMutex) {
            sensors().set(ALLOCATED_NETWORKS, 0);
            sensors().set(SUBNETS, Maps.<String, Cidr>newConcurrentMap());
        }

        sensors().set(SUBNET_ENTITIES, Maps.<String, VirtualNetwork>newConcurrentMap());
        sensors().set(CONTAINER_ADDRESSES, HashMultimap.<String, InetAddress>create());

        ConfigToAttributes.apply(this, MESOS_CLUSTER);
        ConfigToAttributes.apply(this, ETCD_CLUSTER_URL);
    }

    @Override
    public String getIconUrl() { return "classpath://calico-logo.png"; }

    @Override
    public InetAddress getNextContainerAddress(String subnetId) {
        Cidr cidr = getSubnetCidr(subnetId);

        synchronized (addressMutex) {
            Map<String, Integer> allocations = sensors().get(SUBNET_ADDRESS_ALLOCATIONS);
            Integer allocated = allocations.get(subnetId);
            if (allocated == null) allocated = 1;
            InetAddress next = cidr.addressAtOffset(allocated + 1);
            allocations.put(subnetId, allocated + 1);
            sensors().set(SUBNET_ADDRESS_ALLOCATIONS, allocations);
            return next;
        }
    }

    @Override
    public Cidr getNextSubnetCidr(String networkId) {
        synchronized (networkMutex) {
            Cidr networkCidr = getNextSubnetCidr();
            recordSubnetCidr(networkId, networkCidr);
            return networkCidr;
        }
    }

    @Override
    public Cidr getNextSubnetCidr() {
        synchronized (networkMutex) {
            Cidr networkCidr = config().get(CONTAINER_NETWORK_CIDR);
            Integer networkSize = config().get(CONTAINER_NETWORK_SIZE);
            Integer allocated = sensors().get(ALLOCATED_NETWORKS);
            InetAddress baseAddress = networkCidr.addressAtOffset(allocated * (1 << (32 - networkSize)));
            Cidr subnetCidr = new Cidr(baseAddress.getHostAddress() + "/" + networkSize);
            LOG.debug("Allocated {} from {} for subnet #{}", new Object[] { subnetCidr, networkCidr, allocated });
            sensors().set(ALLOCATED_NETWORKS, allocated + 1);
            return subnetCidr;
        }
    }

    @Override
    public void recordSubnetCidr(String networkId, Cidr subnetCidr) {
        synchronized (networkMutex) {
            Map<String, Cidr> subnets = sensors().get(SdnProvider.SUBNETS);
            subnets.put(networkId, subnetCidr);
            sensors().set(SdnProvider.SUBNETS, subnets);
        }
    }

    @Override
    public void recordSubnetCidr(String networkId, Cidr subnetCidr, int allocated) {
        synchronized (networkMutex) {
            recordSubnetCidr(networkId, subnetCidr);
            Map<String, Integer> allocations = sensors().get(SUBNET_ADDRESS_ALLOCATIONS);
            allocations.put(networkId, allocated);
            sensors().set(SUBNET_ADDRESS_ALLOCATIONS, allocations);
        }
    }

    @Override
    public Cidr getSubnetCidr(String networkId) {
        synchronized (networkMutex) {
            Map<String, Cidr> subnets = sensors().get(SdnProvider.SUBNETS);
            return subnets.get(networkId);
        }
    }

    @Override
    public Object getNetworkMutex() { return networkMutex; }

    @Override
    public void start(Collection<? extends Location> locs) {
        addLocations(locs);
        List<Location> locations = MutableList.copyOf(Locations.getLocationsCheckingAncestors(locs, this));

        sensors().set(SERVICE_UP, Boolean.FALSE);

        // Add ouserlves as an extension to the Docker location
        MesosCluster cluster = (MesosCluster) config().get(MESOS_CLUSTER);
        cluster.getDynamicLocation().addExtension(NetworkProvisioningExtension.class, this);

        super.start(locations);

        sensors().set(SERVICE_UP, Boolean.TRUE);
    }

    @Override
    public void stop() {
        sensors().set(SERVICE_UP, Boolean.FALSE);

        super.stop();
    }

    @Override
    public void rebind() {
        super.rebind();
        // TODO implement custom SDN provider rebind logic
    }

    @Override
    public Map<String, Cidr> listManagedNetworkAddressSpace() {
        return ImmutableMap.copyOf(sensors().get(SUBNETS));
    }

    @Override
    public String execCalicoCommand(MesosSlave slave, String command) {
        String etcdUrl = sensors().get(ETCD_CLUSTER_URL);
        Maybe<SshMachineLocation> machine = Machines.findUniqueMachineLocation(slave.getLocations(), SshMachineLocation.class);
        TaskWrapper<String> process = SshEffectorTasks.ssh(BashCommands.sudo(command))
                .environmentVariable("ETCD_AUTHORITY", etcdUrl)
                .machine(machine.get())
                .configure(SshTool.PROP_ALLOCATE_PTY, true)
                .requiringZeroAndReturningStdout()
                .newTask();
        String output = DynamicTasks.queue(process).asTask().getUnchecked();
        return output;
    }

    /** For Calico we use profiles to group containers in networks and add the required IP address to the eth1 calico interface. */
    @Override
    public InetAddress attachNetwork(MesosSlave slave, Entity entity, String containerId, String subnetId) {
        InetAddress address = getNextContainerAddress(subnetId);

        // Run some commands to get information about the container network namespace
        String dockerIpOutput = slave.execCommand(sudo("ip addr show dev docker0 scope global label docker0"));
        String dockerIp = Strings.getFirstWordAfter(dockerIpOutput.replace('/', ' '), "inet");
        String inspect = Strings.trimEnd(slave.execCommand(sudo("docker inspect -f '{{.State.Pid}}' " + containerId)));
        String dockerPid = Iterables.find(Splitter.on(CharMatcher.anyOf("\r\n")).omitEmptyStrings().split(inspect),
                StringPredicates.matchesRegex("^[0-9]+$"));
        Cidr subnetCidr = getSubnetCidr(subnetId);
        String slaveAddressOutput = slave.execCommand(sudo("ip addr show dev eth0 scope global label eth0"));
        String slaveAddress = Strings.getFirstWordAfter(slaveAddressOutput.replace('/', ' '), "inet");

        // Determine whether we are attatching the container to the initial application network
        String applicationId = entity.getApplicationId();
        boolean initial = subnetId.equals(applicationId);

        // Add the container if we have not yet done so
        if (initial) {
            execCalicoCommand(slave, String.format("calicoctl container add %s %s", containerId, address.getHostAddress()));

            // Return its endpoint ID
            String getEndpointId = String.format("calicoctl container %s endpoint-id show", containerId);
            String getEndpointIdStdout = execCalicoCommand(slave, getEndpointId);
            Optional<String> endpointId = Iterables.tryFind(
                    Splitter.on(CharMatcher.anyOf("\r\n")).split(getEndpointIdStdout),
                    StringPredicates.matchesRegex("[0-9a-f]{32}"));
            if (!endpointId.isPresent()) throw new IllegalStateException("Cannot find endpoint-id: " + getEndpointIdStdout);

            // Add to the application profile
            execCalicoCommand(slave, String.format("calicoctl profile add %s", subnetId));
            execCalicoCommand(slave, String.format("calicoctl endpoint %s profile append %s", endpointId.get(), subnetId));
        }

        // Set up the network
        List<String> commands = MutableList.of();
        commands.add(sudo("mkdir -p /var/run/netns"));
        commands.add(BashCommands.ok(sudo(String.format("ln -s /proc/%s/ns/net /var/run/netns/%s", dockerPid, dockerPid))));
        if (initial) {
            commands.add(sudo(String.format("ip netns exec %s ip route del default", dockerPid)));
            commands.add(sudo(String.format("ip netns exec %s ip route add default via %s", dockerPid, dockerIp)));
            commands.add(sudo(String.format("ip netns exec %s ip route add %s via %s", dockerPid, subnetCidr.toString(), slaveAddress)));
        } else {
            commands.add(sudo(String.format("ip netns exec %s ip addr add %s/%d dev eth1", dockerPid, address.getHostAddress(), subnetCidr.getLength())));
        }
        for (String cmd : commands) {
            slave.execCommand(cmd);
        }

        return address;
    }

    @Override
    public void provisionNetwork(VirtualNetwork network) {
        String networkId = network.sensors().get(VirtualNetwork.NETWORK_ID);
        Cidr subnetCidr = SdnUtils.provisionNetwork(this, network);
        boolean ipip = config().get(CalicoModule.USE_IPIP);
        boolean nat = config().get(CalicoModule.USE_NAT);
        String addPool = String.format("calicoctl pool add %s %s %s", subnetCidr, ipip ? "--ipip" : "", nat ? "--nat-outgoing" : "");
        MesosSlave slave = (MesosSlave) getMesosCluster().sensors().get(MesosCluster.MESOS_SLAVES).getMembers().iterator().next();
        execCalicoCommand(slave, addPool);

        // Create a DynamicGroup with all attached entities
        EntitySpec<DynamicGroup> networkSpec = EntitySpec.create(DynamicGroup.class)
                .configure(DynamicGroup.ENTITY_FILTER, Predicates.and(
                        Predicates.not(Predicates.or(Predicates.instanceOf(MarathonTask.class), Predicates.instanceOf(DelegateEntity.class))),
                        MesosUtils.sameCluster(getMesosCluster()),
                        SdnUtils.attachedToNetwork(networkId)))
                .displayName(network.getDisplayName());
        DynamicGroup subnet = sensors().get(SDN_APPLICATIONS).addMemberChild(networkSpec);
        subnet.sensors().set(VirtualNetwork.NETWORK_ID, networkId);
        network.sensors().set(VirtualNetwork.NETWORKED_APPLICATIONS, subnet);

        sensors().get(SDN_NETWORKS).addMember(network);
    }

    @Override
    public void deallocateNetwork(VirtualNetwork network) {
        String networkId = network.sensors().get(VirtualNetwork.NETWORK_ID);
        Optional<Entity> found = Iterables.tryFind(sensors().get(SDN_APPLICATIONS).getMembers(), EntityPredicates.attributeEqualTo(VirtualNetwork.NETWORK_ID, networkId));
        if (found.isPresent()) {
            Entity group = found.get();
            sensors().get(SDN_APPLICATIONS).removeMember(group);
            sensors().get(SDN_APPLICATIONS).removeChild(group);
            Entities.unmanage(group);
        } else {
            LOG.warn("Cannot find group containing {} network entities", networkId);
        }
        sensors().get(SDN_NETWORKS).removeMember(network);

        // TODO actually deprovision the network if possible?
    }

    @Override
    public MesosCluster getMesosCluster() {
        return (MesosCluster) sensors().get(MESOS_CLUSTER);
    }

    static {
        RendererHints.register(SDN_NETWORKS, RendererHints.openWithUrl(DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(SDN_APPLICATIONS, RendererHints.openWithUrl(DelegateEntity.EntityUrl.entityUrl()));
        RendererHints.register(MESOS_CLUSTER, RendererHints.openWithUrl(DelegateEntity.EntityUrl.entityUrl()));
    }

}
