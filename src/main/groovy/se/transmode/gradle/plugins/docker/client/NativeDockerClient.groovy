/*
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
package se.transmode.gradle.plugins.docker.client

import java.util.List;
import java.util.Map;

import com.google.common.base.Preconditions

import org.apache.commons.lang.StringUtils;
import org.gradle.api.GradleException

class NativeDockerClient implements DockerClient {

    private final String binary;

    NativeDockerClient(String binary) {
        Preconditions.checkArgument(binary as Boolean,  "Docker binary can not be empty or null.")
        this.binary = binary
    }

    @Override
    String buildImage(File buildDir, String tag) {
        Preconditions.checkArgument(tag as Boolean,  "Image tag can not be empty or null.")
        def cmdLine = "${binary} build -t ${tag} ${buildDir}"
        return executeAndWait(cmdLine)
    }

    @Override
    String pushImage(String tag) {
        Preconditions.checkArgument(tag as Boolean,  "Image tag can not be empty or null.")
        def cmdLine = "${binary} push ${tag}"
        return executeAndWait(cmdLine)
    }

    private static String executeAndWait(String cmdLine) {
        def process = cmdLine.execute()
        process.waitForProcessOutput(System.out, System.err)
        if (process.exitValue()) {
            throw new GradleException("Docker execution failed\nCommand line [${cmdLine}]")
        }
        return "Done"
    }

    @Override
    String run(String tag, String containerName, String hostName, boolean detached, 
            boolean autoRemove, Map<String, String> env,
            Map<String, String> ports, Map<String, String> volumes, List<String> volumesFrom,
            List<String> links, List<String> dnsIps, List<String> searchDomains) {
        Preconditions.checkArgument(tag as Boolean,  "Image tag cannot be empty or null.")
        Preconditions.checkArgument(env != null,  "Environment map cannot be null.")
        Preconditions.checkArgument(ports != null,  "Exported port map cannot be null.")
        Preconditions.checkArgument(volumes != null,  "Volume map cannot be null.")
        Preconditions.checkArgument(volumesFrom != null,  "Volumes from list cannot be null.")
        Preconditions.checkArgument(links != null,  "Link list cannot be null.")
        Preconditions.checkArgument(dnsIps != null,  "DNS IP list cannot be null.");
        Preconditions.checkArgument(searchDomains != null,  "DNS search list cannot be null.");
        Preconditions.checkArgument(!detached || !autoRemove,
            "Cannot set both detached and autoRemove options to true.");

        def detachedArg = detached ? '-d' : ''
        def removeArg = autoRemove ? '--rm' : ''
        def cmdLine = "${binary} run ${detachedArg} ${removeArg}"
        if (!StringUtils.isEmpty(containerName)) {
            cmdLine = "$cmdLine --name ${containerName}"
        }
        if (!StringUtils.isEmpty(hostName)) {
            cmdLine = "$cmdLine --hostname ${hostName}"
        }
        cmdLine = appendArguments(cmdLine, env, "--env", '=')
        cmdLine = appendArgumentsValueFirst(cmdLine, ports, "--publish")
        cmdLine = appendArguments(cmdLine, volumes, "--volume")
        cmdLine = appendArguments(cmdLine, volumesFrom, "--volumes-from")
        cmdLine = appendArguments(cmdLine, links, "--link")
        cmdLine = appendArguments(cmdLine, dnsIps, "--dns")
        cmdLine = appendArguments(cmdLine, searchDomains, "--dns-search")
        cmdLine = "${cmdLine} ${tag}"
        return executeAndWait(cmdLine)
    }

    private String appendArguments(String cmdLine, Map<String, String> map, String option, 
            String separator = ':') {
        // Add each entry in the map as the indicated argument
        map.each { key, value ->
            cmdLine = "${cmdLine} ${option} ${key}${separator}${value}"
        }
        return cmdLine
    }

    private String appendArgumentsValueFirst(String cmdLine, Map<String, String> map, String option,
            String separator = ':') {
        // Add each entry in the map as the indicated argument
        map.each { key, value ->
            cmdLine = "${cmdLine} ${option} ${value}${separator}${key}"
        }
        return cmdLine
    }
    
    
    private String appendArguments(String cmdLine, List<String> list, String option) {
        // Add each entry in the map as the indicated argument
        list.each {
            cmdLine = "${cmdLine} ${option} ${it}"
        }
        return cmdLine
    }

}
