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
package clocker.docker.networking.entity.sdn.calico;

import clocker.docker.networking.entity.sdn.SdnAgent;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.core.sensor.AttributeSensorAndConfigKey;
import org.apache.brooklyn.entity.software.base.SoftwareProcess;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

import brooklyn.entity.nosql.etcd.EtcdNode;

/**
 * The Calico plugin
 */
@ImplementedBy(CalicoNodeImpl.class)
public interface CalicoNode extends SdnAgent {

    @SetFromFlag("version")
    ConfigKey<String> SUGGESTED_VERSION = ConfigKeys.newConfigKeyWithDefault(SoftwareProcess.SUGGESTED_VERSION, "0.4.8");

    @SetFromFlag("downloadUrl")
    AttributeSensorAndConfigKey<String, String> DOWNLOAD_URL = ConfigKeys.newSensorAndConfigKeyWithDefault(SoftwareProcess.DOWNLOAD_URL,
            "https://github.com/Metaswitch/calico-docker/releases/download/v${version}/calicoctl");

    @SetFromFlag("etcdNode")
    AttributeSensorAndConfigKey<EtcdNode, EtcdNode> ETCD_NODE = ConfigKeys.newSensorAndConfigKey(EtcdNode.class,
            "sdn.calico.etcd.node", "The EtcdNode attached to the same DockerHost as the plugin");

    @SetFromFlag("powerstripPort")
    ConfigKey<Integer> POWERSTRIP_PORT = ConfigKeys.newIntegerConfigKey("powerstrip.port", "Powerstrip port", 2377);

}
