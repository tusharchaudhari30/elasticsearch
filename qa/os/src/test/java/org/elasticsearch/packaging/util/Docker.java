/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.packaging.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static java.nio.file.attribute.PosixFilePermissions.fromString;
import static org.elasticsearch.packaging.util.FileMatcher.p644;
import static org.elasticsearch.packaging.util.FileMatcher.p660;
import static org.elasticsearch.packaging.util.FileMatcher.p755;
import static org.elasticsearch.packaging.util.FileMatcher.p775;
import static org.elasticsearch.packaging.util.FileUtils.getCurrentVersion;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Utilities for running packaging tests against the Elasticsearch Docker images.
 */
public class Docker {
    private static final Log logger = LogFactory.getLog(Docker.class);

    private static final Shell sh = new Shell();
    private static final DockerShell dockerShell = new DockerShell();

    /**
     * Tracks the currently running Docker image. An earlier implementation used a fixed container name,
     * but that appeared to cause problems with repeatedly destroying and recreating containers with
     * the same name.
     */
    private static String containerId = null;

    /**
     * Checks whether the required Docker image exists. If not, the image is loaded from disk. No check is made
     * to see whether the image is up-to-date.
     * @param distribution details about the docker image to potentially load.
     */
    public static void ensureImageIsLoaded(Distribution distribution) {
        final long count = sh.run("docker image ls --format '{{.Repository}}' " + distribution.flavor.name).stdout.lines().count();

        if (count != 0) {
            return;
        }

        logger.info("Loading Docker image: " + distribution.path);
        sh.run("docker load -i " + distribution.path);
    }

    /**
     * Runs an Elasticsearch Docker container.
     * @param distribution details about the docker image being tested.
     */
    public static Installation runContainer(Distribution distribution) throws Exception {
        return runContainer(distribution, null, Collections.emptyMap());
    }

    /**
     * Runs an Elasticsearch Docker container, with options for overriding the config directory
     * through a bind mount, and passing additional environment variables.
     *
     * @param distribution details about the docker image being tested.
     * @param configPath the path to the config to bind mount, or null
     * @param envVars environment variables to set when running the container
     */
    public static Installation runContainer(Distribution distribution, Path configPath, Map<String,String> envVars) throws Exception {
        removeContainer();

        final List<String> args = new ArrayList<>();

        args.add("docker run");

        // Remove the container once it exits
        args.add("--rm");

        // Run the container in the background
        args.add("--detach");

        envVars.forEach((key, value) -> args.add("--env " + key + "=\"" + value + "\""));

        // The container won't run without configuring discovery
        args.add("--env discovery.type=single-node");

        // Map ports in the container to the host, so that we can send requests
        args.add("--publish 9200:9200");
        args.add("--publish 9300:9300");

        if (configPath != null) {
            // Bind-mount the config dir, if specified
            args.add("--volume \"" + configPath + ":/usr/share/elasticsearch/config\"");
        }

        args.add(distribution.flavor.name + ":test");

        final String command = String.join(" ", args);
        logger.debug("Running command: " + command);
        containerId = sh.run(command).stdout.trim();

        waitForElasticsearchToStart();

        return Installation.ofContainer();
    }

    /**
     * Waits for the Elasticsearch process to start executing in the container.
     * This is called every time a container is started.
     */
    private static void waitForElasticsearchToStart() throws InterruptedException {
        boolean isElasticsearchRunning = false;
        int attempt = 0;

        String psOutput;

        do {
            // Give the container a chance to crash out
            Thread.sleep(1000);

            psOutput = dockerShell.run("ps ax").stdout;

            if (psOutput.contains("/usr/share/elasticsearch/jdk/bin/java")) {
                isElasticsearchRunning = true;
                break;
            }

        } while (attempt++ < 5);

        if (!isElasticsearchRunning) {
            final String dockerLogs = sh.run("docker logs " + containerId).stdout;
            fail("Elasticsearch container did start successfully.\n\n" + psOutput + "\n\n" + dockerLogs);
        }
    }

    /**
     * Removes the currently running container.
     */
    public static void removeContainer() {
        if (containerId != null) {
            try {
                // Remove the container, forcibly killing it if necessary
                logger.debug("Removing container " + containerId);
                final String command = "docker rm -f " + containerId;
                final Shell.Result result = sh.runIgnoreExitCode(command);

                if (result.isSuccess() == false) {
                    // I'm not sure why we're already removing this container, but that's OK.
                    if (result.stderr.contains("removal of container " + " is already in progress") == false) {
                        throw new RuntimeException(
                            "Command was not successful: [" + command + "] result: " + result.toString());
                    }
                }
            } finally {
                // Null out the containerId under all circumstances, so that even if the remove command fails
                // for some reason, the other tests will still proceed. Otherwise they can get stuck, continually
                // trying to remove a non-existent container ID.
                containerId = null;
            }
        }
    }

    /**
     * Copies a file from the container into the local filesystem
     * @param from the file to copy in the container
     * @param to the location to place the copy
     */
    public static void copyFromContainer(Path from, Path to) {
        final String script = "docker cp " + containerId + ":" + from + " " + to;
        logger.debug("Copying file from container with: " + script);
        sh.run(script);
    }

    /**
     * Extends {@link Shell} so that executed commands happen in the currently running Docker container.
     */
    public static class DockerShell extends Shell {
        @Override
        protected String[] getScriptCommand(String script) {
            assert containerId != null;

            return super.getScriptCommand("docker exec " +
                "--user elasticsearch:root " +
                "--tty " +
                containerId + " " +
                script);
        }
    }

    /**
     * Checks whether a path exists in the Docker container.
     */
    public static boolean existsInContainer(Path path) {
        logger.debug("Checking whether file " + path + " exists in container");
        final Shell.Result result = dockerShell.runIgnoreExitCode("test -e " + path);

        return result.isSuccess();
    }

    /**
     * Checks that the specified path's permissions and ownership match those specified.
     */
    public static void assertPermissionsAndOwnership(Path path, Set<PosixFilePermission> expectedPermissions) {
        logger.debug("Checking permissions and ownership of [" + path + "]");

        final String[] components = dockerShell.run("stat --format=\"%U %G %A\" " + path).stdout.split("\\s+");

        final String username = components[0];
        final String group = components[1];
        final String permissions = components[2];

        // The final substring() is because we don't check the directory bit, and we
        // also don't want any SELinux security context indicator.
        Set<PosixFilePermission> actualPermissions = fromString(permissions.substring(1, 10));

        assertEquals("Permissions of " + path + " are wrong", actualPermissions, expectedPermissions);
        assertThat("File owner of " + path + " is wrong", username, equalTo("elasticsearch"));
        assertThat("File group of " + path + " is wrong", group, equalTo("root"));
    }

    /**
     * Waits for up to 20 seconds for a path to exist in the container.
     */
    public static void waitForPathToExist(Path path) throws InterruptedException {
        int attempt = 0;

        do {
            if (existsInContainer(path)) {
                return;
            }

            Thread.sleep(1000);
        } while (attempt++ < 20);

        fail(path + " failed to exist after 5000ms");
    }

    /**
     * Perform a variety of checks on an installation. If the current distribution is not OSS, additional checks are carried out.
     */
    public static void verifyContainerInstallation(Installation installation, Distribution distribution) {
        verifyOssInstallation(installation);
        if (distribution.flavor == Distribution.Flavor.DEFAULT) {
            verifyDefaultInstallation(installation);
        }
    }

    private static void verifyOssInstallation(Installation es) {
        dockerShell.run("id elasticsearch");
        dockerShell.run("getent group elasticsearch");

        final Shell.Result passwdResult = dockerShell.run("getent passwd elasticsearch");
        final String homeDir = passwdResult.stdout.trim().split(":")[5];
        assertThat(homeDir, equalTo("/usr/share/elasticsearch"));

        Stream.of(
            es.home,
            es.data,
            es.logs,
            es.config
        ).forEach(dir -> assertPermissionsAndOwnership(dir, p775));

        Stream.of(
            es.plugins,
            es.modules
        ).forEach(dir -> assertPermissionsAndOwnership(dir, p755));

        // FIXME these files should all have the same permissions
        Stream.of(
            "elasticsearch.keystore",
//            "elasticsearch.yml",
            "jvm.options"
//            "log4j2.properties"
        ).forEach(configFile -> assertPermissionsAndOwnership(es.config(configFile), p660));

        Stream.of(
            "elasticsearch.yml",
            "log4j2.properties"
        ).forEach(configFile -> assertPermissionsAndOwnership(es.config(configFile), p644));

        assertThat(
            dockerShell.run(es.bin("elasticsearch-keystore") + " list").stdout,
            containsString("keystore.seed"));

        Stream.of(
            es.bin,
            es.lib
        ).forEach(dir -> assertPermissionsAndOwnership(dir, p755));

        Stream.of(
            "elasticsearch",
            "elasticsearch-cli",
            "elasticsearch-env",
            "elasticsearch-enve",
            "elasticsearch-keystore",
            "elasticsearch-node",
            "elasticsearch-plugin",
            "elasticsearch-shard"
        ).forEach(executable -> assertPermissionsAndOwnership(es.bin(executable), p755));

        Stream.of(
            "LICENSE.txt",
            "NOTICE.txt",
            "README.textile"
        ).forEach(doc -> assertPermissionsAndOwnership(es.home.resolve(doc), p644));
    }

    private static void verifyDefaultInstallation(Installation es) {
        Stream.of(
            "elasticsearch-certgen",
            "elasticsearch-certutil",
            "elasticsearch-croneval",
            "elasticsearch-saml-metadata",
            "elasticsearch-setup-passwords",
            "elasticsearch-sql-cli",
            "elasticsearch-syskeygen",
            "elasticsearch-users",
            "x-pack-env",
            "x-pack-security-env",
            "x-pack-watcher-env"
        ).forEach(executable -> assertPermissionsAndOwnership(es.bin(executable), p755));

        // at this time we only install the current version of archive distributions, but if that changes we'll need to pass
        // the version through here
        assertPermissionsAndOwnership(es.bin("elasticsearch-sql-cli-" + getCurrentVersion() + ".jar"), p755);

        Stream.of(
            "role_mapping.yml",
            "roles.yml",
            "users",
            "users_roles"
        ).forEach(configFile -> assertPermissionsAndOwnership(es.config(configFile), p660));
    }
}
