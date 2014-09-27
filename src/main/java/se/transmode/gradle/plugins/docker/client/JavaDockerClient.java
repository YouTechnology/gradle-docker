/**
 * Copyright 2014 Transmode AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.transmode.gradle.plugins.docker.client;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang.StringUtils;

import com.github.dockerjava.api.NotFoundException;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Link;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ports.Binding;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig.DockerClientConfigBuilder;
import com.github.dockerjava.core.DockerClientImpl;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;

public class JavaDockerClient extends DockerClientImpl implements DockerClient {

    JavaDockerClient(DockerClientConfig config) {
        super(config);
    }

    @Override
    public String buildImage(File buildDir, String tag) {
        Preconditions.checkNotNull(tag, "Image tag can not be null.");
        Preconditions.checkArgument(!tag.isEmpty(),  "Image tag can not be empty.");
        try (InputStream output = buildImageCmd(buildDir).withTag(tag).exec()) {
            ByteStreams.copy(output, System.out);
        } catch (Exception e) {
            throw new RuntimeException("Encountered exception building image: " + e.getMessage(), e);
        }
        return "Done";
    }

    @Override
    public String pushImage(String tag) {
        Preconditions.checkNotNull(tag, "Image tag can not be null.");
        Preconditions.checkArgument(!tag.isEmpty(),  "Image tag can not be empty.");
        try (InputStream output = pushImageCmd(tag).exec()) {
            ByteStreams.copy(output, System.out);
        } catch (Exception e) {
            throw new RuntimeException("Encountered exception pushing image: " + e.getMessage(), e);
        }
        return "Done";
    }

    public static JavaDockerClient create(String url, String user, String password, String email) {
        DockerClientConfigBuilder builder = DockerClientConfig.createDefaultConfigBuilder()
                .withUsername(user)
                .withPassword(password)
                .withEmail(email);
        if (!StringUtils.isEmpty(url)) {
            builder.withUri(url);
        }
        return new JavaDockerClient(builder.build());
    }

    @Override
    public String run(String tag, String containerName, String hostName, boolean detached, 
            boolean autoRemove, Map<String, String> env, Map<String, String> ports, 
            Map<String, String> volumes, List<String> volumesFrom, List<String> links,
            List<String> dnsIps, List<String> searchDomains) {
        
        Preconditions.checkArgument(!StringUtils.isEmpty(tag),  
                "Image tag cannot be empty or null.");
        Preconditions.checkArgument(env != null,  "Environment map cannot be null.");
        Preconditions.checkArgument(ports != null,  "Exported port map cannot be null.");
        Preconditions.checkArgument(volumes != null,  "Volume map cannot be null.");
        Preconditions.checkArgument(volumesFrom != null,  "Volumes from list cannot be null.");
        Preconditions.checkArgument(links != null,  "Link list cannot be null.");
        Preconditions.checkArgument(dnsIps != null,  "DNS IP list cannot be null.");
        Preconditions.checkArgument(searchDomains != null,  "DNS search list cannot be null.");
        Preconditions.checkArgument(!detached || !autoRemove, 
                "Cannot set both detached and autoRemove options to true.");
        
        // Start by creating the container
        CreateContainerCmd createCmd = createContainerCmd(tag);
        if (!StringUtils.isEmpty(containerName)) {
            createCmd.withName(containerName);
        }
        if (!StringUtils.isEmpty(hostName)) {
            createCmd.withHostName(hostName);
        }
        createCmd.withEnv(constructEnvironment(env));
        createCmd.withExposedPorts(constructExposedPorts(ports));
        CreateContainerResponse createResponse;
        try {
            createResponse = createCmd.exec();
        } catch (NotFoundException nfe) {
            // TODO have option to pull image
            throw nfe;
        }
        String containerId = createResponse.getId();
        
        // Configure start command
        StartContainerCmd startCmd = startContainerCmd(containerId);
        startCmd.withBinds(constructBinds(volumes));
        startCmd.withVolumesFrom(StringUtils.join(volumesFrom, ","));
        startCmd.withLinks(constructLinks(links));
        startCmd.withPortBindings(constructPorts(ports));
        startCmd.withDns(dnsIps.toArray(new String[dnsIps.size()]));
        
        // Start the container
        try {
            startCmd.exec();
        } catch (Exception e) {
            // Want to get rid of container we created
            removeContainerCmd(containerId).exec();
        }
       
        // Should we wait around and/or remove the container on exit
        if (autoRemove) {
            return removeOnExit(containerId);
        } else if (detached) {
            return containerId;
        } else {
            return waitForExit(containerId);
        }
    }

    @VisibleForTesting
    static String[] constructEnvironment(Map<String, String> env) {
        String[] envList = new String[env.size()];
        int index = 0;
        for (Entry<String, String> entry : env.entrySet()) {
            String envSetting = String.format("%s=%s", entry.getKey(), entry.getValue());
            envList[index++] = envSetting;
        }
        return envList;
    }

    @VisibleForTesting
    static ExposedPort[] constructExposedPorts(Map<String, String> ports) {
        int index = 0;
        ExposedPort[] exposedPorts = new ExposedPort[ports.size()];
        for (Entry<String, String> entry : ports.entrySet()) {
            exposedPorts[index++] = createExposedPort(entry.getKey());
        }
        return exposedPorts;
    }

    private static ExposedPort createExposedPort(String value) {
        ExposedPort exposedPort;
        if (value.indexOf('/') > -1) {
            exposedPort = ExposedPort.parse(value);
        } else {
            exposedPort = ExposedPort.tcp(Integer.parseInt(value));
        }
        return exposedPort;
    }

    @VisibleForTesting
    static Bind[] constructBinds(Map<String, String> volumes) {
        int index;
        Bind[] binds = new Bind[volumes.size()];
        index = 0;
        for (Entry<String, String> entry : volumes.entrySet()) {
            Volume vol = new Volume(entry.getValue());
            Bind bind = new Bind(entry.getKey(), vol);
            binds[index++] = bind;
        }
        return binds;
    }
    
    @VisibleForTesting
    static Link[] constructLinks(List<String> links) {
        int index;
        Link[] linkArr = new Link[links.size()];
        index = 0;
        for (String linkStr : links) {
            String[] values = linkStr.split(":");
            Link link = new Link(values[0], values.length == 2 ? values[1] : values[0]);
            linkArr[index++] = link;
        }
        return linkArr;
    }

    @VisibleForTesting
    static Ports constructPorts(Map<String, String> portMappings) {
        Ports ports = new Ports();
        for (Entry<String, String> entry : portMappings.entrySet()) {
            // Construct host port binding
            Binding hostBinding;
            String[] hostAddr = entry.getValue().split(":");
            if (hostAddr.length == 1) {
                hostBinding = Ports.Binding("0.0.0.0", Integer.parseInt(hostAddr[0]));
            } else {
                hostBinding = Ports.Binding(hostAddr[0], Integer.parseInt(hostAddr[1]));
            }
            
            ports.bind(createExposedPort(entry.getKey()), hostBinding);
        }
        return ports;
    }

    private String removeOnExit(String containerId) {
        String exitStatus = waitForExit(containerId);
        removeContainerCmd(containerId).exec();
        return exitStatus;
    }

    private String waitForExit(String containerId) {
        // TODO -- show container output if/when we get that option from docker-java
        return "Exit status: " + waitContainerCmd(containerId).exec();
    }
}
