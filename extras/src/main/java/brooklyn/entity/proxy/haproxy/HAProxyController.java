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
package brooklyn.entity.proxy.haproxy;

import org.apache.brooklyn.api.entity.ImplementedBy;
import org.apache.brooklyn.config.ConfigKey;
import org.apache.brooklyn.core.config.ConfigKeys;
import org.apache.brooklyn.entity.proxy.AbstractController;
import org.apache.brooklyn.util.core.flags.SetFromFlag;

@ImplementedBy(HAProxyControllerImpl.class)
public interface HAProxyController extends AbstractController {

    @SetFromFlag("haProxyConfig")
    ConfigKey<String> HAPROXY_CONFIG_TEMPLATE_URL = ConfigKeys.newStringConfigKey(
            "haproxy.config.templateUrl",
            "Configuration template file (in freemarker format) for HAProxyController",
            "classpath://brooklyn/entity/proxy/haproxy/haproxy.cfg");

    @SetFromFlag("backendMode")
    ConfigKey<String> BACKEND_MODE = ConfigKeys.newStringConfigKey(
            "haproxy.backend.mode", "The mode of the backend", "http");

    @SetFromFlag("frontendMode")
    ConfigKey<String> FRONTEND_MODE = ConfigKeys.newStringConfigKey(
            "haproxy.frontend.mode", "The mode of the frontend", "http");

    @SetFromFlag("bindAddress")
    ConfigKey<String> BIND_ADDRESS = ConfigKeys.newStringConfigKey(
            "haproxy.frontend.bind.address", "The address frontend should bind to. If unset all" +
                    "IPv4 addresses on the server will be listened on.");

}
